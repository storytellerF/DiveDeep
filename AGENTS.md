# Project Rules

## General

- Keep changes scoped to the requested behavior. Do not mix unrelated refactors into feature or test changes.
- Prefer the existing Kotlin, Gradle, Compose, and shell script patterns in this repository.
- The shared module currently targets Android, iOS, and JVM only. JS/WASM web targets are disabled because shared `commonMain` uses Room/SQLite for translation caching.
- After code changes, run the narrowest checks that cover the touched behavior. Broaden to the full checks below when shared Android, IPC, workflow, or end-to-end behavior changes.

## Testing

Use this baseline check before handing off Android/Kotlin changes:

```bash
./gradlew detekt :shared:jvmTest :shared:testAndroidHostTest :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest :androidTestFixture:assembleDebug --no-daemon
```

For Android test architecture, prerequisites, commands, reports, and recovery,
follow [DEVELOPMENT.md](DEVELOPMENT.md#android-end-to-end-tests).

- Serialize every device operation with the environment's shared device-lock helper.
  Use its absolute installed path; do not assume a helper is vendored in this repo.
- Preserve real cross-app LLMD IPC coverage. Never substitute mock translation for
  `OverlayLlmdIntegrationTest`; model-free regressions are a separate suite.
- The host runner owns the original device/app snapshot; Kotlin cases own only
  their temporary baseline. Keep that boundary and stop rollback on failed prerequisites.
- For host E2E changes, run `python -m unittest discover -s test/e2e/unit -p 'test_*.py' -v`.
  These stateful boundary/process tests do not access a device.
- Preserve cross-process crash/recovery coverage and detached-descendant process
  tests when changing the host runner or its platform adapters.
- A `needs-recovery` result is unresolved work. Preserve its private journal/backups,
  recover under the external device lease, and verify before reusing the device.
- For Android test/build changes, also compile `:androidApp:assembleDebugAndroidTest`
  and run affected device cases when the required device/model is available.
- Do not run Gradle directly from LLMD's generated Android directory for a full APK
  build. Use its Tauri CLI as documented in DEVELOPMENT.md.
