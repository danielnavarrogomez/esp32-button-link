#ifndef LED_BUILTIN
#define LED_BUILTIN 2
#endif

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

const char *BLE_DEVICE_NAME = "ESP32-POST-POC";
// Fixed custom UUIDs for the ESP32 Button Link app. You can change them later if you
// build multiple ESP32 device families and want separate BLE services.
const char *BLE_SERVICE_UUID = "8f3a5c2e-1b7d-4a5e-9c72-4f41c6d7a001";
const char *BLE_CHARACTERISTIC_UUID = "8f3a5c2e-1b7d-4a5e-9c72-4f41c6d7a002";
const char *postUrl = "https://httpbin.org/post";
const char *buttonName = "GPIO27";
const int optoInputPin = 27;
const int ledPin = LED_BUILTIN;
const unsigned int requiredPulseCount = 10;
const unsigned long pulseWindowMs = 2000;
const unsigned long debounceMs = 20;
const unsigned long ledOnMs = 2000;

BLECharacteristic *pCharacteristic = nullptr;
bool deviceConnected = false;

bool stableOptoActive = false;
bool lastRawOptoActive = false;
unsigned long rawChangedAt = 0;
unsigned long lastTriggerAt = 0;
unsigned long firstPulseAt = 0;
unsigned long ledUntil = 0;
unsigned long sequenceNumber = 0;
unsigned int pulseCount = 0;

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

void sendJsonToPhone(const String &json) {
  if (deviceConnected && pCharacteristic != nullptr) {
    pCharacteristic->setValue(json.c_str());
    pCharacteristic->notify();
    Serial.println("JSON notification sent: " + json);
  } else {
    Serial.println("No BLE client connected, notification skipped: " + json);
  }
}

void sendPostRequestToPhone(const String &trigger, const String &detail) {
  if (millis() - lastTriggerAt < 750) {
    return;
  }

  lastTriggerAt = millis();
  sequenceNumber++;

  const String body =
      "{\"device\":\"ESP32-POST-POC\",\"button\":\"" + String(buttonName) +
      "\",\"trigger\":\"" + trigger +
      "\",\"detail\":\"" + detail +
      "\",\"sequence\":" + String(sequenceNumber) +
      ",\"uptime_ms\":" + String(millis()) + "}";

  // Full URLs are preserved for this proof of concept. For production, prefer
  // sending action names instead of full secret webhook URLs over BLE.
  const String message =
      "{\"type\":\"http_post\",\"url\":\"" + String(postUrl) +
      "\",\"content_type\":\"application/json\",\"body\":" + body + "}";

  sendJsonToPhone(message);
}

void setup() {
  Serial.begin(115200);
  pinMode(optoInputPin, INPUT_PULLUP);
  pinMode(ledPin, OUTPUT);
  digitalWrite(ledPin, LOW);
  stableOptoActive = digitalRead(optoInputPin) == LOW;
  lastRawOptoActive = stableOptoActive;

  setupBLE();
}

void registerCompletedPulse() {
  const unsigned long now = millis();

  if (pulseCount == 0 || now - firstPulseAt > pulseWindowMs) {
    firstPulseAt = now;
    pulseCount = 1;
  } else {
    pulseCount++;
  }

  if (pulseCount >= requiredPulseCount && now - firstPulseAt <= pulseWindowMs) {
    ledUntil = now + ledOnMs;
    sendPostRequestToPhone("opto_burst", "10 on/off pulses within 2 seconds");
    pulseCount = 0;
    firstPulseAt = 0;
  }
}

void updateOptoPulseDetector() {
  const bool rawOptoActive = digitalRead(optoInputPin) == LOW;
  const unsigned long now = millis();

  if (rawOptoActive != lastRawOptoActive) {
    lastRawOptoActive = rawOptoActive;
    rawChangedAt = now;
  }

  if (rawOptoActive != stableOptoActive && now - rawChangedAt >= debounceMs) {
    stableOptoActive = rawOptoActive;

    if (!stableOptoActive) {
      registerCompletedPulse();
    }
  }

  if (pulseCount > 0 && now - firstPulseAt > pulseWindowMs) {
    pulseCount = 0;
    firstPulseAt = 0;
  }
}

void loop() {
  updateOptoPulseDetector();
  digitalWrite(ledPin, millis() < ledUntil ? HIGH : LOW);
  delay(2);
}
