#ifndef LED_BUILTIN
#define LED_BUILTIN 2
#endif

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// ---------------------------------------------------------------------------
// BLE identity and GATT contract
// ---------------------------------------------------------------------------
// These UUIDs must match the Android app. Change them only if you also update
// the app constants.
const char *BLE_DEVICE_NAME = "ESP32 Button Link";
const char *BLE_SERVICE_UUID = "8f3a5c2e-1b7d-4a5e-9c72-4f41c6d7a001";
const char *BLE_CHARACTERISTIC_UUID = "8f3a5c2e-1b7d-4a5e-9c72-4f41c6d7a002";

// ---------------------------------------------------------------------------
// Button configuration
// ---------------------------------------------------------------------------
// Add one entry per physical input. The Android app matches actions by the
// buttonName field, so keep it stable once customers have configured actions.
//
// inputMode:
//   INPUT         Use when the external circuit drives both HIGH and LOW.
//   INPUT_PULLUP  Use for active-low buttons/modules, including the tested
//                 PC817/EL817 optocoupler board on GPIO27.
//   INPUT_PULLDOWN Use for active-high dry contacts when appropriate.
//
// activeLow:
//   true  means the input is considered pressed/active when digitalRead is LOW.
//   false means the input is considered pressed/active when digitalRead is HIGH.
//
// triggerPressCount + pressWindowMs:
//   The BLE notification is sent when this many completed presses happen within
//   the configured window. A completed press is counted when the input returns
//   from active to inactive after debounce.
//
// outputPin + outputOnMs:
//   outputPin is turned on for outputOnMs after a valid trigger. Set outputPin
//   to -1 if this button should not drive any local output.
struct ButtonConfig {
  const char *buttonName;
  uint8_t inputPin;
  uint8_t inputMode;
  bool activeLow;
  uint16_t triggerPressCount;
  unsigned long pressWindowMs;
  unsigned long debounceMs;
  int outputPin;
  bool outputActiveHigh;
  unsigned long outputOnMs;
};

ButtonConfig buttons[] = {
    // Default/example config used in the current installation:
    // PC817/EL817 optocoupler output on GPIO27, active-low, trigger after
    // 10 completed on/off pulses within 2 seconds, onboard LED on for 2 seconds.
    {"GPIO27", 27, INPUT_PULLUP, true, 10, 2000, 20, LED_BUILTIN, true, 2000},

    // Add more inputs here, for example:
    // {"GPIO26", 26, INPUT_PULLUP, true, 3, 1000, 20, LED_BUILTIN, true, 1000},
};

const size_t buttonCount = sizeof(buttons) / sizeof(buttons[0]);

// Ignore a second valid trigger from the same input during this short interval.
// This prevents repeated sends caused by contact bounce near the end of a burst.
const unsigned long triggerCooldownMs = 750;

// ---------------------------------------------------------------------------
// Runtime state
// ---------------------------------------------------------------------------
struct ButtonState {
  bool stableActive;
  bool lastRawActive;
  unsigned long rawChangedAt;
  unsigned long firstPressAt;
  unsigned long outputUntil;
  unsigned long lastTriggerAt;
  uint16_t pressCount;
};

ButtonState states[buttonCount];

BLECharacteristic *pCharacteristic = nullptr;
bool deviceConnected = false;
unsigned long sequenceNumber = 0;

bool isInputActive(const ButtonConfig &button) {
  const bool high = digitalRead(button.inputPin) == HIGH;
  return button.activeLow ? !high : high;
}

void writeOutput(const ButtonConfig &button, bool on) {
  if (button.outputPin < 0) {
    return;
  }
  const int activeLevel = button.outputActiveHigh ? HIGH : LOW;
  digitalWrite(button.outputPin, on ? activeLevel : !activeLevel);
}

class MyServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *pServer) {
    deviceConnected = true;
    Serial.println("BLE client connected");
  }

  void onDisconnect(BLEServer *pServer) {
    deviceConnected = false;
    Serial.println("BLE client disconnected");
    BLEDevice::startAdvertising();
    Serial.println("BLE advertising restarted");
  }
};

void setupBLE() {
  BLEDevice::init(BLE_DEVICE_NAME);

  BLEServer *pServer = BLEDevice::createServer();
  pServer->setCallbacks(new MyServerCallbacks());

  BLEService *pService = pServer->createService(BLE_SERVICE_UUID);
  pCharacteristic = pService->createCharacteristic(
      BLE_CHARACTERISTIC_UUID,
      BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
  pCharacteristic->addDescriptor(new BLE2902());
  pCharacteristic->setValue("{\"status\":\"ready\"}");

  pService->start();
  BLEDevice::getAdvertising()->addServiceUUID(BLE_SERVICE_UUID);
  BLEDevice::getAdvertising()->setScanResponse(true);
  BLEDevice::getAdvertising()->start();

  Serial.println("BLE started");
}

void notifyPhone(const String &json) {
  if (deviceConnected && pCharacteristic != nullptr) {
    pCharacteristic->setValue(json.c_str());
    pCharacteristic->notify();
    Serial.println("BLE notification sent: " + json);
  } else {
    Serial.println("No BLE client connected, notification skipped: " + json);
  }
}

void sendButtonTrigger(const ButtonConfig &button) {
  sequenceNumber++;

  const String message =
      "{\"device\":\"" + String(BLE_DEVICE_NAME) +
      "\",\"event\":\"button_trigger\"" +
      ",\"button\":\"" + String(button.buttonName) +
      "\",\"pin\":" + String(button.inputPin) +
      ",\"press_count\":" + String(button.triggerPressCount) +
      ",\"window_ms\":" + String(button.pressWindowMs) +
      ",\"sequence\":" + String(sequenceNumber) +
      ",\"uptime_ms\":" + String(millis()) + "}";

  notifyPhone(message);
}

void setupButtons() {
  for (size_t i = 0; i < buttonCount; i++) {
    const ButtonConfig &button = buttons[i];
    ButtonState &state = states[i];

    pinMode(button.inputPin, button.inputMode);
    if (button.outputPin >= 0) {
      pinMode(button.outputPin, OUTPUT);
      writeOutput(button, false);
    }

    state.stableActive = isInputActive(button);
    state.lastRawActive = state.stableActive;
    state.rawChangedAt = 0;
    state.firstPressAt = 0;
    state.outputUntil = 0;
    state.lastTriggerAt = 0;
    state.pressCount = 0;
  }
}

void setup() {
  Serial.begin(115200);
  setupButtons();
  setupBLE();
}

void registerCompletedPress(size_t index) {
  ButtonConfig &button = buttons[index];
  ButtonState &state = states[index];
  const unsigned long now = millis();

  if (state.pressCount == 0 || now - state.firstPressAt > button.pressWindowMs) {
    state.firstPressAt = now;
    state.pressCount = 1;
  } else {
    state.pressCount++;
  }

  if (state.pressCount >= button.triggerPressCount &&
      now - state.firstPressAt <= button.pressWindowMs &&
      now - state.lastTriggerAt >= triggerCooldownMs) {
    state.lastTriggerAt = now;
    state.outputUntil = now + button.outputOnMs;
    sendButtonTrigger(button);
    state.pressCount = 0;
    state.firstPressAt = 0;
  }
}

void updateButton(size_t index) {
  const ButtonConfig &button = buttons[index];
  ButtonState &state = states[index];
  const bool rawActive = isInputActive(button);
  const unsigned long now = millis();

  if (rawActive != state.lastRawActive) {
    state.lastRawActive = rawActive;
    state.rawChangedAt = now;
  }

  if (rawActive != state.stableActive && now - state.rawChangedAt >= button.debounceMs) {
    state.stableActive = rawActive;

    if (!state.stableActive) {
      registerCompletedPress(index);
    }
  }

  if (state.pressCount > 0 && now - state.firstPressAt > button.pressWindowMs) {
    state.pressCount = 0;
    state.firstPressAt = 0;
  }
}

void updateOutputs() {
  const unsigned long now = millis();

  for (size_t i = 0; i < buttonCount; i++) {
    const int outputPin = buttons[i].outputPin;
    if (outputPin < 0) {
      continue;
    }

    bool outputOn = false;
    for (size_t j = 0; j < buttonCount; j++) {
      if (buttons[j].outputPin == outputPin && now < states[j].outputUntil) {
        outputOn = true;
        break;
      }
    }
    writeOutput(buttons[i], outputOn);
  }
}

void loop() {
  for (size_t i = 0; i < buttonCount; i++) {
    updateButton(i);
  }
  updateOutputs();
  delay(2);
}
