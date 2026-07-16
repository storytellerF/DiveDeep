# Project Rules

## General

- Keep changes scoped to the requested behavior. Do not mix unrelated refactors into feature or test changes.
- Prefer the existing Kotlin, Gradle, Compose, and shell script patterns in this repository.
- After code changes, run the narrowest checks that cover the touched behavior. Broaden to the full checks below when shared Android, IPC, workflow, or end-to-end behavior changes.

## Testing

Use this baseline check before handing off Android/Kotlin changes:

```bash
./gradlew detekt :shared:jvmTest :shared:testAndroidHostTest :androidApp:assembleDebug :androidTestFixture:assembleDebug --no-daemon
```

For JavaScript end-to-end test helpers, at least syntax-check the scripts:

```bash
node --check test/e2e/android-appium.js
node --check test/e2e/android-authorize-llmd.js
node --check test/e2e/android-toggle.js
```

Android end-to-end tests use a real device or emulator and must be serialized with the shared Appium device lock:

```bash
/home/mint-dev/.codex/skills/android-appium-device-lock/scripts/adb-device-lock.sh run \
  --serial "$DEVICE" \
  --project-dir "$PWD" \
  --test-name "divedeep-android-e2e" \
  --max-timeout-seconds 3600 \
  --wait-timeout-seconds 3600 \
  -- env DEVICE="$DEVICE" ./scripts/android-e2e.sh
```

The end-to-end test must use the real llmd IPC service, not a mock. Before running it, make sure:

- An Android device or emulator is connected, selected with `DEVICE`, and unlocked.
- Appium and the `uiautomator2` driver are installed through the repository npm dependencies.
- llmd package `com.storytellerf.llmd` is installed and exposes `com.storytellerf.llmd.action.BIND_IPC`.
- llmd has the test model in its app-private files, currently `files/models/gemma-4-E2B-it.litertlm`.
- DiveDeep package `com.storyteller_f.divedeep` is authorized in llmd. The script opens the llmd authorization page and confirms access through Appium.

If llmd is missing, `scripts/android-e2e.sh` can install it from `LLMD_APK`, or build it from `LLMD_REPO` or a sibling `../llmd` checkout. Build llmd Android APKs through the Tauri CLI from the llmd app directory:

```bash
cd /home/mint-dev/Forks/llmd/app
npm run tauri android build -- --debug --apk
```

Do not use Gradle directly from llmd's generated `src-tauri/gen/android` directory for the full APK build. When changing llmd IPC or authorization behavior, compile llmd first, rebuild and install the APK, then rerun the DiveDeep end-to-end script.
