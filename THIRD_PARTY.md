# Third-party notices

This project builds on the following open-source work. None of it is affiliated
with Apple Inc.

## go-ios (danielpaulus/go-ios)

- https://github.com/danielpaulus/go-ios
- License: MIT
- `libgoios.so` (packaged as `app/src/main/jniLibs/arm64-v8a/libgoios.so`) is
  compiled from go-ios **v1.3.2** by `scripts/build-goios.sh`; the source is not
  vendored in this repository.
- It provides the Apple-side protocols (usbmuxd client, lockdown, pairing,
  TLS, `com.apple.amfi.lockdown`).

## ios-location-simulator (qyr0721)

- https://github.com/qyr0721/ios-location-simulator
- License: MIT, Copyright (c) 2026 iosloc contributors
- The USB layer is derived from this project:
  - `app/src/main/java/com/ryanoy/iosdevmode/usb/AppleUsbDevice.kt`
  - `app/src/main/java/com/ryanoy/iosdevmode/usb/UsbMuxTransport.kt`
  - `app/src/main/java/com/ryanoy/iosdevmode/usb/MuxStream.kt`
  - `app/src/main/java/com/ryanoy/iosdevmode/usb/UsbMuxDaemon.kt`
  - `app/src/main/java/com/ryanoy/iosdevmode/plist/PlistIO.kt`
- It implements the Apple usbmux protocol over Android's USB host API and serves
  the standard usbmuxd socket on `127.0.0.1:27015` for go-ios.

## dd-plist (3breadt/dd-plist)

- https://github.com/3breadt/dd-plist
- License: MIT
- Used to encode/decode property lists in the usbmuxd bridge.

## Android Gradle Plugin / Kotlin / Gradle

- Apache License 2.0
