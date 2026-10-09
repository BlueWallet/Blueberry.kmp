This is a Kotlin Multiplatform project targeting Android, iOS, Desktop (JVM).

### Release

Press [Run workflow](https://github.com/BlueWallet/Blueberry.kmp/actions/workflows/release.yml).

That publishes a GitHub Release from `master`. The tag is `vYYYY.MM.DD` from the UTC date. A second run on the same UTC day stops. A pushed `v` tag starts the same workflow.

* [/iosApp](./iosApp/iosApp) contains an iOS application. Even if you’re sharing your UI with Compose Multiplatform,
  you need this entry point for your iOS app. This is also where you should add SwiftUI code for your project.

* [/shared](./shared/src) is for code that will be shared across your Compose Multiplatform applications.
  It contains several subfolders:
  - [commonMain](./shared/src/commonMain/kotlin) is for code that’s common for all targets.
  - Other folders are for Kotlin code that will be compiled for only the platform indicated in the folder name.
    For example, if you want to use Apple’s CoreCrypto for the iOS part of your Kotlin app,
    the [iosMain](./shared/src/iosMain/kotlin) folder would be the right place for such calls.
    Similarly, if you want to edit the Desktop (JVM) specific part, the [jvmMain](./shared/src/jvmMain/kotlin)
    folder is the appropriate location.

### Prerequisites

- JDK 11+
- Android SDK (set `ANDROID_HOME` / `ANDROID_SDK_ROOT`, or add `sdk.dir=...` to `local.properties`)
- For a physical Android device: USB debugging enabled, device visible via `adb devices`

The first Gradle build may download SDK components (for example Android SDK Platform 37) and can take several minutes.

### Clone

This repo uses git submodules for the Bitcoin KMP libraries in `vendor/`. After clone, initialize them (the vendor directories are empty until you do):

```bash
git submodule update --init
```

### Running the apps

Use the run configurations provided by the run widget in your IDE's toolbar. You can also use these commands and options:

- Android app (build APK only): `./gradlew :androidApp:assembleDebug`
- Android app (physical device over USB):
  ```bash
  adb devices   # confirm your device is listed
  ./gradlew :androidApp:installDebug
  adb shell am start -n io.bluewallet.blueberry/.MainActivity
  ```
- Desktop app:
  - Hot reload: `./gradlew :desktopApp:hotRun --auto`
  - Standard run: `./gradlew :desktopApp:run`
- iOS app: open the [/iosApp](./iosApp) directory in Xcode and run it from there.

### Running tests

Use the run button in your IDE's editor gutter, or run tests using Gradle tasks:

- Android tests: `./gradlew :shared:testAndroidHostTest`
- Desktop tests: `./gradlew :shared:jvmTest`
- iOS tests: `./gradlew :shared:iosSimulatorArm64Test`

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)…