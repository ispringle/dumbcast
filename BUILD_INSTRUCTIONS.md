# Building dumbcast

## Prerequisites

1. **Android SDK** - Install via Android Studio or command-line tools
   - Required API level: 27 (Android 8.1.0)
   - Build tools version: 27.0.3 or later

2. **Java Development Kit** - JDK 8 or later

## Setup Steps

### 1. Install Android SDK

**Option A: Via Android Studio**
- Download from https://developer.android.com/studio
- Install Android 8.1 (API 27) SDK via SDK Manager
- Note the SDK path (usually `~/Library/Android/sdk` on macOS)

**Option B: Via Command Line Tools**
```bash
# Download SDK command-line tools
# Install platform and build tools
sdkmanager "platforms;android-27"
sdkmanager "build-tools;27.0.3"
```

### 2. Configure SDK Path

Create `local.properties` in project root:
```properties
sdk.dir=/path/to/your/android/sdk
```

Or set environment variable:
```bash
export ANDROID_HOME=/path/to/your/android/sdk
```

### 3. Build the App

**Debug build:**
```bash
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

**Release build (with ProGuard):**
```bash
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release-unsigned.apk`

### 4. Install on Device

**Via ADB:**
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

**Via file transfer:**
- Copy APK to device
- Open with file manager
- Install (may need to enable "Unknown sources")

## Expected Build Results

**Debug APK size:** ~1-2 MB
**Release APK size (ProGuard):** ~500KB-1MB

The small size is due to:
- Pure Java (no Kotlin runtime)
- No external dependencies
- ProGuard optimization for release builds

## Running Tests

**Unit tests:**
```bash
./gradlew test
```

**Instrumented tests (requires connected device/emulator):**
```bash
./gradlew connectedAndroidTest
```

## Troubleshooting

**Build fails with "SDK location not found":**
- Ensure `local.properties` exists with correct `sdk.dir`
- Or set `ANDROID_HOME` environment variable

**Missing API 27:**
```bash
sdkmanager "platforms;android-27"
```

**Gradle daemon issues:**
```bash
./gradlew --stop
./gradlew assembleDebug
```

## Target Device

- **Primary:** Sonim XP5800 (Android 8.1.0, API 27)
- **Also works on:** Any Android 8.1+ device with physical keypad
- **Touchscreen:** Supported but not required
