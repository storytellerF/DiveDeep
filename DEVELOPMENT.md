# Development

## Android SDK

Install Android SDK Platform 37.1 (`platforms;android-37.1`) and accept its SDK
license before building. The Android app, shared library, and test fixture use
API 37 with minor API level 1 because the updated Compose Android dependencies
require it. Major and minor compile SDK versions are configured in
`gradle/libs.versions.toml`; the target and minimum SDK versions are independent.

The build uses AGP 9.4.1 with Gradle 9.8.2 and compile SDK 37.1. Keep these
versions compatible rather than suppressing unsupported compile SDK warnings.

## Validation

Run the Android/Kotlin baseline from the repository root:

```sh
./gradlew detekt :shared:jvmTest :shared:testAndroidHostTest :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest :androidTestFixture:assembleDebug --no-daemon
```

After changing Android build tooling or settings fields, also build both release
variants and inspect individual deprecations:

```sh
./gradlew :androidApp:assembleRelease :androidApp:assembleDaily --warning-mode all --no-daemon
```

Without signing credentials these builds produce unsigned APKs. For settings UI
verification, select the HTTP backend, edit the API key, save, and reopen settings
to confirm persistence. The key must remain fully masked while typing and after
saving; switching to llmd disables the field without clearing it.

With an unlocked Android device selected through `ANDROID_SERIAL`, the focused
Compose instrumentation tests cover masked editing, saving the entered key,
disabled-field retention, and replacement when a new configuration is loaded:

```sh
./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.storyteller_f.divedeep.SettingsApiKeyTest --no-daemon
```

Acquire the shared device lock before running this command on a shared device.
These tests render an isolated settings screen and do not invoke llmd IPC.

`AndroidSecureSettingsTest` covers native settings read failures, preservation of
foreign accessibility services, and rejected deletion confirmation on failed
readback. It also checks the direct provider query on the device without writing
secure settings; failure cases use injected cursors and a recording command sink.

### Android end-to-end tests

The Windows/Linux host runner `scripts/android_e2e.py` owns the whole run: APK preparation,
a private pre-run snapshot, command trees, cancellation, restoration and reports.
The Bash entry `scripts/android-e2e.sh` forwards arguments only. Windows callers
should use Python directly and cancel with Ctrl+C or Ctrl+Break; Git Bash
`kill -TERM` against native Python is a forced termination and cannot run Python
cleanup.

Kotlin/JUnit + UIAutomator own each case's baseline, LLMD authorization and the
cross-app assertions. No Appium server/session or npm dependencies are needed.
The former npm authorization/toggle helpers are replaced by native JUnit tests.
The settings controls tests cover explicit enable/disable transitions and saving
the Local IPC backend with each Release/Daily/Debug target. Real LLMD cases are
excluded from ordinary instrumentation discovery by `RealLlmdIntegration`.

Acquire the environment's shared device lease before installation or any device
test. Keep the full run and recovery within that lease. The lock helper belongs to
the environment and is not vendored or reimplemented here. Host work defaults to
1200 seconds plus a separate 120-second cleanup budget and up to 10 seconds per
command-tree closure check; size the lease above that
bound, and do not release/reuse a device whose cleanup is unconfirmed.

Model-free checks:

```sh
python -m unittest discover -s test/e2e/unit -p 'test_*.py' -v
ANDROID_SERIAL="$DEVICE" ./gradlew :androidApp:connectedDebugAndroidTest \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true --no-daemon
```

The host tests use stateful device boundaries and real native child processes;
they do not claim device/model coverage. They assert that unknown reads authorize
no writes, foreign services remain intact, rollback stops on failed prerequisites,
backups survive incomplete restoration, and child/grandchild processes stop before
restoration, including descendants that start new sessions. Fresh-process tests
forcibly exit during claim, test execution, rollback and disposal, then discover
and resume recovery in a new host. Faults are also injected before and after side
effects. CI runs these checks on Windows and Ubuntu.

For the real suite, install Python 3.10+, a JDK and the Android SDK. Unlock the
leased device. Install LLMD with `com.storytellerf.llmd.action.BIND_IPC`, import
`gemma-4-E2B-it.litertlm`, select it, switch the LLMD service on and wait for Ready.
A model file on disk alone does not establish readiness.

Inside the lease, run:

```sh
python scripts/android_e2e.py --device "$DEVICE" --llmd-variant debug
# Optional provider installation/source-build inputs:
python scripts/android_e2e.py --device "$DEVICE" --llmd-variant daily --llmd-apk /path/to/llmd-daily.apk
python scripts/android_e2e.py --device "$DEVICE" --llmd-variant debug --llmd-repo ../llmd
```

`DEVICE`, `ADB`, `LLMD_VARIANT`, `LLMD_APK`, `LLMD_REPO` and `E2E_REPORT_DIR` are
also accepted as environment defaults. Gradle receives the same device through
`ANDROID_SERIAL`. The default LLMD variant is Release. Optional source builds
support Debug only and use `npm run tauri android build -- --debug --apk` from
LLMD's `app` directory; Node/npm are needed only for that optional provider build.
Do not invoke Gradle directly from LLMD's generated Android directory.
An installed package without the required IPC service follows the same upgrade
path as a missing package. Installation is followed by a fresh capability check;
an unreadable capability response fails without authorizing an upgrade.

`OverlayLlmdIntegrationTest` reports four independent contracts: translated fixture
overlay, underlying-page interaction, actual source/result detail UI, and removal
of buttons and open details after service destruction. Unique fixture titles
require fresh real-model translations. Mock translation is disabled. Each case
opens the selected LLMD authorization page and verifies the caller package before
clicking Allow; real Binder translation establishes successful authorization.
This authorization persists as before. The suite preserves other accessibility
services with `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`.

The host sets an on-device run marker and supplies the matching `hostRunId`,
`realLlmd=true`, `llmdVariant` and class/filter instrumentation arguments. Cases
reject direct invocation without matching host ownership. They finish with
DiveDeep disabled and the fixture stopped. They never restore the pre-run snapshot;
that belongs exclusively to the host after instrumentation and its native process
tree have stopped.

Restoration is ordered and verified: disable only DiveDeep's service, confirm
its app and fixture are stopped, restore the private preferences, restore the
original secure settings, verify, then dispose of backups and the run marker.
Failure stops dependent writes. Read errors are exceptions; only a successful
explicit probe can establish absence. Windows uses a Job Object. Linux uses a
dedicated child subreaper which adopts orphaned descendants, including Gradle's
detached single-use daemon, and acknowledges closure only after reaping the entire
tree. Host exit closes its control pipe and initiates descendant cleanup. An
unacknowledged supervisor exit blocks device restoration. Other host platforms
fail before starting commands; they require an equivalent process-scope adapter.

Binary preference copies stay on-device. A Base64 recovery copy and typed journal
are retained in a stable private registry, never in the report tree. These files
may contain a saved API key. The default registry is
`%LOCALAPPDATA%/DiveDeep/e2e/runs` on Windows and
`${XDG_STATE_HOME:-~/.local/state}/DiveDeep/e2e/runs` on Linux, indexed by a hash of
the device serial and the run ID. Override with `--state-dir` or `E2E_STATE_DIR`;
use the same registry when resuming. The host publishes the recovery path in its
initial report and stdout before changing the device. Journal updates use an
atomic replacement and flush to disk before their corresponding side effects.

A failed rollback exits with status `needs-recovery` (exit code 2), keeps its marker
and prints the private journal path. New runs reject an outstanding marker or
local journal. After reconnecting the device, acquire its lease and discover the
pending journal using the device's run marker. If marker publication or removal
was interrupted, the sole pending local journal is used instead:

```sh
python scripts/android_e2e.py --device "$DEVICE" --recover
# Explicit paths remain supported for older journals or a different registry:
python scripts/android_e2e.py --device "$DEVICE" --recover /private/directory/run.json
```

If host process closure could not be confirmed, first confirm that the previous
runner and its command descendants have stopped before attempting recovery.
Recovery requires matching device/run ownership. It can resume interrupted
rollback and disposal. Do not remove the marker, backups, or journal to bypass a
failed recovery. If external app removal destroyed the on-device backup, preserve
the host Base64 copy for manual recovery. Hard termination, host loss and offline
devices cannot guarantee immediate cleanup; the retained record is unresolved work.

Results:

- Host `run.json`, Gradle logs, scoped diagnostic log and native UI artifacts:
  `test/e2e/reports/<run-id>/`, or `--report-dir`/`E2E_REPORT_DIR`.
- JUnit XML: `androidApp/build/outputs/androidTest-results/connected/`.
- HTML: `androidApp/build/reports/androidTests/connected/`.
- Native screenshots/hierarchies originate in the app's external
  `files/e2e/<run-id>` directory. Authorization screenshots are kept per case;
  failed cases also capture UI hierarchy and diagnostic logs.

After verification, record device/Android version, LLMD variant/model, commands,
pass/fail counts and any unavailable platform/model combinations.

### iOS simulator

The **iOS Test** workflow (`.github/workflows/ios-test.yml`) runs
`:shared:iosSimulatorArm64Test` on an Apple Silicon `macos-15` runner for relevant
pull requests and manual dispatches. It exercises the shared common tests on the
iOS simulator and uploads test results. It does not test the SwiftUI app.

To run the same check locally, use an Apple Silicon Mac with Xcode selected,
its license accepted, and an iOS simulator runtime installed:

```sh
./gradlew :shared:iosSimulatorArm64Test --warning-mode all --no-daemon
```

Windows and Linux cannot run this task. A disabled task on those hosts is not
evidence that iOS tests passed; check the macOS workflow result instead.

### Gradle deprecation diagnosis

Use `--warning-mode all` to see individual warnings. To include their call sites,
add `-Dorg.gradle.deprecation.trace=true` (quote this argument in PowerShell).
With the current dependencies, the remaining warnings originate inside plugins:

| Warning | Origin from the deprecation trace |
| --- | --- |
| Unsupported `HotReloadUsageType` attribute (Gradle 10) | Compose Hot Reload 1.2.0, `HotReloadUsage.kt` |
| `KotlinNativeBundleArtifactsTypes` enum attribute (Gradle 10) | Kotlin Gradle plugin 2.4.21, `KotlinNativeBundleArtifactFormat.kt` |
| `Configuration.setVisible` (Gradle 11) | AGP 9.4.1, Detekt 2.0.0-alpha.6 and KSP 2.3.12 configuration creation |

The Kotlin warning's generic suggestion to upgrade from KGP 2.0 does not apply:
this project already uses 2.4.21. These plugin calls still need compatible
upstream releases before adopting Gradle 10/11. Do not hide the warnings or
modify plugin internals in project scripts. The former Project dependency
notation warning is no longer emitted after upgrading AGP from 9.2.1 to 9.4.1.
