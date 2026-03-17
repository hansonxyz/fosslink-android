# Building FossLink for Android

## Prerequisites

### JDK 17

Android Gradle Plugin 8.x requires JDK 17.

```bash
# Ubuntu/Debian
sudo apt install openjdk-17-jdk

# Verify
java -version
```

### Android SDK

Install the Android SDK via [Android Studio](https://developer.android.com/studio) or the [command-line tools](https://developer.android.com/studio#command-line-tools-only).

Required SDK components:
- Android SDK Platform 36 (compileSdk)
- Android SDK Build-Tools 36
- Android SDK Platform-Tools

Set `ANDROID_HOME` to point to your SDK installation, or create a `local.properties` file in the project root:

```properties
sdk.dir=/path/to/android-sdk
```

## Building

### Debug build

```bash
ANDROID_HOME=/path/to/android-sdk ./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

The debug build uses the `xyz.hanson.fosslink.debug` application ID and can be installed alongside the release version.

### Release build

Release builds require a signing keystore. Place it at `release.keystore` in the project root and configure credentials in `app/build.gradle.kts`.

```bash
ANDROID_HOME=/path/to/android-sdk ./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`

## Installing

```bash
# Via ADB (USB or wireless debugging)
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Project Structure

| Path | Description |
|------|-------------|
| `app/src/main/java/xyz/hanson/xyzconnect/` | Kotlin source |
| `app/src/main/java/.../service/` | Background connection service, boot receiver |
| `app/src/main/java/.../network/` | WebSocket client, UDP discovery |
| `app/src/main/java/.../sms/` | SMS/MMS read, sync, send, event handling |
| `app/src/main/java/.../contacts/` | Contact sync and migration |
| `app/src/main/java/.../gallery/` | Photo gallery scanning and events |
| `app/src/main/java/.../filesystem/` | WebDAV file server |
| `app/src/main/java/.../ui/` | Jetpack Compose UI screens |
| `app/src/main/res/` | Resources (icons, strings, themes) |
| `docs/` | Protocol and feature specifications |
