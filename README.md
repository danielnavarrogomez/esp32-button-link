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
Device name:         ESP32-POST-POC
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

The included ESP32 proof-of-concept sends a larger JSON payload:

```json
{
  "type": "http_post",
  "url": "https://httpbin.org/post",
  "content_type": "application/json",
  "body": {
    "device": "ESP32-POST-POC",
    "button": "GPIO27",
    "trigger": "opto_burst",
    "detail": "10 on/off pulses within 2 seconds",
    "sequence": 1,
    "uptime_ms": 12345
  }
}
```

The app uses `body.button` first when present, so the included sketch maps to the app action named `GPIO27`.

## Android App

The app lets you:

- Select the ESP32 BLE device from a scan list.
- Start or stop a foreground BLE listener.
- Create up to 10 URL actions.
- Assign each URL action to a button/GPIO name such as `GPIO27`.
- Send `GET` or `POST` requests from the phone.
- Add Basic Auth or form/query variables.

### Phone Setup

1. Install the debug APK or build it from source.
2. Open **ESP32 Button Link**.
3. Grant Bluetooth and notification permissions.
4. Tap **Select ESP32** and choose `ESP32-POST-POC`.
5. Add a tap/action.
6. Set **Button / GPIO name** to `GPIO27`.
7. Set the URL and method.
8. Tap **Start listener**.
9. Trigger the ESP32 input.

The app runs a foreground service while listening, so Android shows a persistent notification. Disable battery optimization for the app if your phone stops background BLE connections.

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

### Trigger Logic

The proof-of-concept sketch:

- Watches GPIO27.
- Counts completed on/off pulses.
- Requires 10 on/off pulses within 2 seconds.
- Turns the ESP32 onboard LED on for 2 seconds.
- Sends a BLE notification with `button:"GPIO27"`.

Change this constant to map another ESP32 input name to the Android app:

```cpp
const char *buttonName = "GPIO27";
```

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
