# Audiobook Reader Android App

An Android application that can read local PDF and EPUB files using text-to-speech.

## Features
- Browse and select local PDF/EPUB files
- Extract text from documents
- Convert text to speech using Android TTS
- Play/pause/stop controls
- Background playback
- File management

## Prerequisites
- Android Studio Arctic Fox or newer
- Android SDK 21+ (minSdkVersion 21)
- JDK 8 or 11

## Building the APK

### Option 1: Using Android Studio (Recommended)
1. Open Android Studio
2. Select "Open an existing project"
3. Navigate to the `audiobook-app` directory
4. Wait for Gradle sync to complete
5. Click Build > Build Bundle(s) / APK(s) > Build APK(s)
6. Find the APK at `app/build/outputs/apk/debug/app-debug.apk`

### Option 2: Using Command Line
```bash
# Navigate to project directory
cd audiobook-app

# Make gradlew executable
chmod +x gradlew

# Build debug APK
./gradlew assembleDebug

# The APK will be at:
# app/build/outputs/apk/debug/app-debug.apk
```

### Option 3: Building Release APK (for distribution)
```bash
# Generate a signing key if you don't have one:
# keytool -genkeypair -v -keystore my-release-key.jks -alias alias_name \
#   -keyalg RSA -keysize 2048 -validity 10000

# Set environment variables for signing:
# export STORE_PASSWORD=your_keystore_password
# export KEY_PASSWORD=your_key_password

# Build release APK
./gradlew assembleRelease

# The signed APK will be at:
# app/build/outputs/apk/release/app-release.apk
```

## Dependencies
- AndroidX AppCompat
- AndroidX Material
- AndroidX ConstraintLayout
- PDFBox Android (for PDF text extraction)
- ePubLib (for EPUB parsing)
- Android TextToSpeech

## Permissions Required
- READ_EXTERNAL_STORAGE
- WRITE_EXTERNAL_STORAGE (for API < 29)

## Usage
1. Launch the app
2. Grant storage permissions when prompted
3. Browse to your PDF or EPUB files
4. Tap a file to start listening
5. Use playback controls to pause/resume/stop