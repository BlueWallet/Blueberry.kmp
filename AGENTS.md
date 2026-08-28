# Agent notes

Operational guardrails for automated work in this repo.

## Before building

- Run `git submodule update --init` if `vendor/*` directories are empty. Gradle fails with "Missing vendor/..." otherwise.
- Ensure the Android SDK is configured (`local.properties`, `ANDROID_HOME`, or `ANDROID_SDK_ROOT`).

## Android on a physical device

- Check `adb devices` before install or launch.
- Use `./gradlew :androidApp:installDebug` to build and install on a connected device. `assembleDebug` only produces the APK.
- Launch: `adb shell am start -n io.bluewallet.blueberry/.MainActivity`
- Application ID: `io.bluewallet.blueberry`

## Platform limits

- iOS simulator builds and `iosSimulatorArm64Test` require macOS. Do not attempt them on Linux.
- Desktop: `./gradlew :desktopApp:run` or `./gradlew :desktopApp:hotRun --auto`

## Screen chrome

- Header actions (Settings, Back, Cancel) use `TextAction`.
- New title + Back screens use `ScreenHeader`.
- Primary actions (Receive, Send, Clear, Continue) use `PillButton`.
- Do not put `PillButton` in a screen header.

## Verification

- Do not claim the app is running without evidence (`adb devices`, successful install, or a running process via `adb shell pidof io.bluewallet.blueberry`).
