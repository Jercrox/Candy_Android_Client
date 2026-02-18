# Candy Android Client

This project is the Android client for the Candy VPN project:
https://github.com/lanthora/candy

This client is built with a Go mobile library for the protocol logic.

## Download APK

You can download the latest version for Android directly from the Releases:
https://github.com/Jercrox/Candy_Android_Client/releases/tag/candy-android-vpn-client

## Prerequisites for compiling the Android client

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

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.


