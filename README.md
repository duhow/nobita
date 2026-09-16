# Nobita

<p align="center">
  <strong>Record Bluetooth communication from your phone</strong>
</p>

<p align="center">
  <a href="https://github.com/duhow/nobita/actions/workflows/build.yml">
    <img src="https://github.com/duhow/nobita/actions/workflows/build.yml/badge.svg" alt="Build">
  </a>
  <img src="https://img.shields.io/badge/Android-12%2B-brightgreen?logo=android" alt="Android 12+">
  <img src="https://img.shields.io/badge/Kotlin-2.x-7F52FF?logo=kotlin" alt="Kotlin">
</p>

Nobita is an Android application for recording Bluetooth communication directly from an Android device, using [Shizuku](https://shizuku.rikka.app/) instead of requiring a computer.

## Capture workflow

1. Start Shizuku through ADB or Wireless Debugging and authorize Nobita.
2. Select a paired/nearby device, or enter its MAC address or name.
3. Tap **Start capture**, use the other Bluetooth application, then tap **Stop & export**.
4. Nobita creates a Wireshark-compatible PCAPNG under `Download/BluetoothCaptures`.

The capture is a live, local stream from Android's `btsnoop_net` endpoint at `127.0.0.1:8872`. Enable both Bluetooth HCI snoop logging and its socket in Developer options before starting; Nobita does not change Bluetooth settings or restart the stack. Device filtering is applied only during export using Bluetooth connection handles; a raw BTSnoop copy can optionally be retained. Captures contain sensitive payloads and are never uploaded automatically.

The endpoint is firmware-dependent and supports one live client. Devices without a compatible listener are reported as unsupported before capture begins. The current implementation targets Android 12+ behavior and requires a working Shizuku UserService.

### Why Nobita?

Because he has a crush on Shizuku. And Bluetooth contained in its name.  
Yeah, a bad joke.
