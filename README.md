# ESP32 Button Link

ESP32 Button Link is a small Android app plus an ESP32 Arduino sketch for turning ESP32 GPIO/button events into phone-side HTTP actions.

The ESP32 does not use Wi-Fi and does not make HTTP requests itself. It sends a BLE GATT notification to the Android app. The Android app listens for the notification, reads the logical button name such as `GPIO27`, then calls the URL action configured for that button.

## Repository Layout

```text
app/                         Android app
esp32/ESP32ButtonLink/       Arduino ESP32 sketch
```

## BLE Contract

The Android app listens for this custom BLE service and characteristic:

```text
Device name:         ESP32 Button Link
Service UUID:        8f3a5c2e-1b7d-4a5e-9c72-4f41c6d7a001
Characteristic UUID: 8f3a5c2e-1b7d-4a5e-9c72-4f41c6d7a002
Properties:          READ, NOTIFY
Encoding:            UTF-8 JSON string
```

The app accepts any of these fields as the logical button name:

```json
{"button":"GPIO27"}
{"gpio":"GPIO27"}
{"trigger":"GPIO27"}
{"name":"GPIO27"}
```

The included ESP32 sketch sends a trigger event like this:

```json
{
  "device": "ESP32 Button Link",
  "event": "button_trigger",
  "button": "GPIO27",
  "pin": 27,
  "press_count": 10,
  "window_ms": 2000,
  "sequence": 1,
  "uptime_ms": 12345
}
```

The app maps this event to the URL action whose **Button / GPIO name** is `GPIO27`.

## Android App

The app lets you:

- Pair the ESP32 using Android Companion Device Manager, with a manual scan/address fallback.
- Automatically start a foreground BLE listener after a device is selected.
- Restart the listener after app launch, app update, or phone boot when permissions allow it.
- Ask Android to observe the paired ESP32 companion device presence and wake the app when it appears.
- Create up to 10 URL actions.
- Assign each URL action to a button/GPIO name such as `GPIO27`.
- Send `GET` or `POST` requests from the phone.
- Add Basic Auth or form/query variables.

### Phone Setup

1. Install the debug APK or build it from source.
2. Open **ESP32 Button Link**.
3. Grant Bluetooth and notification permissions.
4. Tap **Pair ESP32** and choose `ESP32 Button Link` in the Android system dialog.
5. Add a tap/action.
6. Set **Button / GPIO name** to `GPIO27`.
7. Set the URL and method.
8. The listener starts automatically. Use **Stop listener** only when you explicitly want to pause it.
9. Trigger the ESP32 input.

The app runs a foreground service while listening, so Android shows a persistent notification. During pairing, Android may ask to exclude the app from battery optimization; allow it or set battery usage to **Unrestricted** for production reliability.

### Build Android APK

Install Android Studio or Android SDK command-line tools, then run:

```bash
./gradlew assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install on a connected phone:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## ESP32 Sketch

The included sketch is in:

```text
esp32/ESP32ButtonLink/ESP32ButtonLink.ino
```

It is written for:

- Board: ESP32 Dev Module
- Chip tested: ESP32-D0WD-V3
- Arduino ESP32 core with built-in BLE library

### Hardware Wiring

For the one-channel PC817/EL817 optocoupler module used during testing:

Output side of module to ESP32:

```text
Module VCC -> ESP32 3V3
Module GND -> ESP32 GND
Module OUT -> ESP32 GPIO27
```

Input side of module:

```text
External signal + -> module input +
External signal - -> module input -
```

Do not connect the external signal directly to ESP32 GPIO pins. Keep it on the optocoupler input side.

### Configure Inputs

The sketch is configured from this array near the top of `ESP32ButtonLink.ino`:

```cpp
ButtonConfig buttons[] = {
    {"GPIO27", 27, INPUT_PULLUP, true, 10, 2000, 20, LED_BUILTIN, true, 2000},
};
```

Each row is:

```cpp
{
  "Button name sent to Android",
  inputPin,
  inputMode,
  activeLow,
  triggerPressCount,
  pressWindowMs,
  debounceMs,
  outputPin,
  outputActiveHigh,
  outputOnMs
}
```

Fields:

- `buttonName`: name sent over BLE. The Android action must use the same **Button / GPIO name**.
- `inputPin`: ESP32 GPIO number to monitor.
- `inputMode`: usually `INPUT_PULLUP`, `INPUT_PULLDOWN`, or `INPUT`.
- `activeLow`: `true` when the input is active/pressed at `LOW`; `false` when active/pressed at `HIGH`.
- `triggerPressCount`: number of completed presses needed to trigger.
- `pressWindowMs`: time window for the required completed presses.
- `debounceMs`: debounce time for noisy contacts or optocoupler outputs.
- `outputPin`: local output to turn on after a trigger, for example `LED_BUILTIN`; use `-1` for no local output.
- `outputActiveHigh`: `true` if the output turns on at `HIGH`; `false` if it turns on at `LOW`.
- `outputOnMs`: how long the local output stays on after a trigger.

A completed press is counted when the input returns from active to inactive after debounce.

The default config used in this installation:

- Watches GPIO27.
- Uses `INPUT_PULLUP`.
- Treats `LOW` as active.
- Requires 10 completed on/off pulses within 2 seconds.
- Turns the ESP32 onboard LED on for 2 seconds.
- Sends a BLE notification with `button:"GPIO27"`.

To add another input, add another row:

```cpp
ButtonConfig buttons[] = {
    {"GPIO27", 27, INPUT_PULLUP, true, 10, 2000, 20, LED_BUILTIN, true, 2000},
    {"GPIO26", 26, INPUT_PULLUP, true, 3, 1000, 20, LED_BUILTIN, true, 1000},
};
```

Then add an Android action whose **Button / GPIO name** matches the new name, for example `GPIO26`.

### Upload With Arduino CLI

Example:

```bash
arduino-cli compile --fqbn esp32:esp32:esp32:UploadSpeed=115200 esp32/ESP32ButtonLink
arduino-cli upload -p /dev/cu.usbserial-1420 --fqbn esp32:esp32:esp32:UploadSpeed=115200 esp32/ESP32ButtonLink
```

Use the serial port that matches your ESP32.

## Security Notes

The phone app stores URL actions and credentials in normal app preferences for this prototype. For production use, move secrets into encrypted storage and avoid broadcasting full secret webhook URLs from the ESP32. Prefer sending only button names or action names over BLE.

## License

MIT. See [LICENSE](LICENSE).
