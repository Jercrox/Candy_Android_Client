# Candy Android Client

This project is the Android client for Candy VPN, built with a Go mobile library for the protocol logic.

## Prerequisites

- Go 1.18+
- Android Studio / Android SDK
- `gomobile` tool installed:
  ```bash
  go install golang.org/x/mobile/cmd/gomobile@latest
  gomobile init
  ```

## Building the Go Library

1. Navigate to the `candy_go` directory:
   ```bash
   cd candy_go
   ```
2. Build the Android AAR:
   ```bash
   gomobile bind -target=android -o ../app/libs/candy.aar .
   ```
   *Note: You may need to create the `../app/libs` directory if it doesn't exist.*

## Building the APK

1. Open this `Android` folder in Android Studio.
2. Sync the project with Gradle files.
3. Build and Run on your device/emulator.

## Configuration

The app expects a configuration text file with the following format (standard Candy cfg):
```ini
websocket = wss://your-server.com/username/network
password = your_password
name = your_device_name
```
Load this file using the "Gear" icon in the app.
