This is a Kotlin Multiplatform project targeting Android, iOS, Web, Desktop (JVM).

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

### Running the apps

Use the run configurations provided by the run widget in your IDE's toolbar. You can also use these commands and options:

- Android app: `./gradlew :androidApp:assembleDebug`
- Desktop app:
  - Hot reload: `./gradlew :desktopApp:hotRun --auto`
  - Standard run: `./gradlew :desktopApp:run`
- Web app:
  - Wasm target (faster, modern browsers): `./gradlew :webApp:wasmJsBrowserDevelopmentRun`
  - JS target (slower, supports older browsers): `./gradlew :webApp:jsBrowserDevelopmentRun`
- iOS app: open the [/iosApp](./iosApp) directory in Xcode and run it from there.

### Running tests

Use the run button in your IDE's editor gutter, or run tests using Gradle tasks:

- Static analysis: `./gradlew detekt`
- Android tests: `./gradlew :shared:testAndroidHostTest`
- Desktop tests: `./gradlew :shared:jvmTest`
- Web tests:
  - Wasm target: `./gradlew :shared:wasmJsTest`
  - JS target: `./gradlew :shared:jsTest`
- iOS tests: `./gradlew :shared:iosSimulatorArm64Test`

### Android E2E

Run the Android accessibility smoke test with:

```bash
./scripts/android-e2e.sh
```

The E2E test uses the real local llmd IPC service and does not enable mock translation. The script checks for an installed llmd
package exposing `com.storytellerf.llmd.LlmdIpcService` before changing accessibility settings. If llmd is missing, either pass an
existing APK or point the script at an llmd checkout:

```bash
LLMD_APK=/path/to/app-universal-debug.apk ./scripts/android-e2e.sh
LLMD_REPO=../llmd ./scripts/android-e2e.sh
```

When `LLMD_REPO` is set, the script builds llmd from the Tauri app directory with `npm run tauri android build -- --debug --apk`
and installs the newest generated APK. Do not build llmd for this test by running Gradle directly from llmd's
`app/src-tauri/gen/android` directory; that generated build expects Tauri mobile build context and can fail with a refused
WebSocket connection. DiveDeep opens the llmd authorization screen before saving the local IPC backend, and llmd must authorize
the installed DiveDeep package before IPC requests succeed.

The script uses Appium with the UiAutomator2 driver to enable DiveDeep through the app UI. It runs `npm ci` when
`node_modules` is missing, starts Appium on `127.0.0.1:4723` when no server is already running, and installs the
UiAutomator2 driver when needed. Set `DEVICE`, `APPIUM_HOST`, or `APPIUM_PORT` to override the defaults.

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html),
[Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform/#compose-multiplatform),
[Kotlin/Wasm](https://kotl.in/wasm/)…

We would appreciate your feedback on Compose/Web and Kotlin/Wasm in the public Slack channel [#compose-web](https://slack-chats.kotlinlang.org/c/compose-web).
If you face any issues, please report them on [YouTrack](https://youtrack.jetbrains.com/newIssue?project=CMP).
