<h1 align="center">FossLink for Android</h1>

<p align="center">The Android companion app for <a href="https://github.com/hansonxyz/fosslink-desktop">FossLink Desktop</a>.<br>Bridges your phone's SMS/MMS to your computer over your local network.</p>

## What It Does

FossLink for Android runs as a background service on your phone. It connects to the FossLink desktop app over your local Wi-Fi network and provides access to:

- **SMS/MMS** — Send and receive text messages from your desktop
- **Contacts** — Sync contact names and photos to the desktop app
- **Phone gallery** — Browse and download photos and videos
- **File access** — WebDAV server for accessing phone storage
- **Find my phone** — Ring your phone from your desktop
- **Battery status** — Monitor phone battery level from desktop

## How It Works

1. Install FossLink on both your phone and computer
2. Both devices discover each other via UDP broadcast on your local network
3. Pair the devices when prompted
4. The phone maintains a background WebSocket connection to the desktop app
5. All communication stays on your local network — nothing goes through the cloud

## Requirements

- Android 8.0 (API 26) or higher
- Same Wi-Fi network as the desktop app

## Downloads

Get the latest APK from [Releases](https://github.com/hansonxyz/fosslink-android/releases/latest).

## Building From Source

See [BUILD.md](BUILD.md) for full instructions.

```bash
# Debug build
ANDROID_HOME=/path/to/android-sdk ./gradlew assembleDebug

# Release build (requires signing keystore)
ANDROID_HOME=/path/to/android-sdk ./gradlew assembleRelease
```

## Permissions

FossLink requests only the permissions it needs:

| Permission | Purpose |
|-----------|---------|
| SMS/MMS | Read and send text messages |
| Contacts | Sync contact names and photos to desktop |
| Notifications | Show connection status, capture phone notifications |
| Storage | Access photos and files for gallery and WebDAV |
| Phone | Detect incoming calls |
| Foreground Service | Keep the connection alive in the background |
| Boot Completed | Auto-start the service after phone reboot |

## Tech Stack

- **Kotlin** — Modern Android development
- **Jetpack Compose** — Material 3 UI
- **OkHttp** — WebSocket communication
- **Coroutines** — Async operations

## License

Licensed under the [MIT License](LICENSE).
