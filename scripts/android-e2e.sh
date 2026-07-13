#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="${ADB:-adb}"
DEVICE="${DEVICE:-}"
SERVICE="com.storyteller_f.divedeep/com.storyteller_f.divedeep.DiveDeepAccessibilityService"
APP_PACKAGE="com.storyteller_f.divedeep"
FIXTURE_PACKAGE="com.storyteller_f.divedeep.fixture"
LLMD_PACKAGE="dev.placeholder.llmd"
LLMD_SERVICE_CLASS="dev.placeholder.llmd.LlmdIpcService"

adb_cmd() {
  if [[ -n "$DEVICE" ]]; then
    "$ADB" -s "$DEVICE" "$@"
  else
    "$ADB" "$@"
  fi
}

read_setting() {
  adb_cmd shell settings get secure "$1" | tr -d '\r'
}

write_setting() {
  adb_cmd shell settings put secure "$1" "$2"
}

restore_setting() {
  local key="$1"
  local value="$2"
  if [[ "$value" == "null" ]]; then
    adb_cmd shell settings delete secure "$key" >/dev/null
  else
    write_setting "$key" "$value"
  fi
}

append_service() {
  local current="$1"
  if [[ "$current" == "null" || -z "$current" ]]; then
    printf '%s' "$SERVICE"
  elif [[ ":$current:" == *":$SERVICE:"* ]]; then
    printf '%s' "$current"
  else
    printf '%s:%s' "$current" "$SERVICE"
  fi
}

wake_device() {
  adb_cmd shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  adb_cmd shell wm dismiss-keyguard >/dev/null 2>&1 || true
}

require_llmd_service() {
  if ! adb_cmd shell pm path "$LLMD_PACKAGE" | tr -d '\r' | grep -q '^package:'; then
    echo "Local llmd package is not installed: $LLMD_PACKAGE" >&2
    exit 1
  fi

  if ! adb_cmd shell dumpsys package "$LLMD_PACKAGE" | tr -d '\r' | grep -Fq "$LLMD_SERVICE_CLASS"; then
    echo "Local llmd IPC service is unavailable: $LLMD_SERVICE_CLASS" >&2
    exit 1
  fi
}

set_dive_deep_enabled_pref() {
  local enabled="$1"
  adb_cmd shell "run-as $APP_PACKAGE sh -c 'mkdir -p shared_prefs && printf \"%s\n\" \"<?xml version=\\\"1.0\\\" encoding=\\\"utf-8\\\" standalone=\\\"yes\\\" ?>\" \"<map>\" \"    <boolean name=\\\"enabled\\\" value=\\\"$enabled\\\" />\" \"</map>\" > shared_prefs/dive_deep_state.xml'"
}

cleanup() {
  set +e
  set_dive_deep_enabled_pref false >/dev/null 2>&1
  adb_cmd shell am force-stop "$APP_PACKAGE" >/dev/null 2>&1
  adb_cmd shell am force-stop "$FIXTURE_PACKAGE" >/dev/null 2>&1
  restore_setting enabled_accessibility_services "$OLD_SERVICES" >/dev/null 2>&1
  restore_setting accessibility_enabled "$OLD_ACCESSIBILITY_ENABLED" >/dev/null 2>&1
}

cd "$ROOT_DIR"

require_llmd_service

OLD_SERVICES="$(read_setting enabled_accessibility_services)"
OLD_ACCESSIBILITY_ENABLED="$(read_setting accessibility_enabled)"
trap cleanup EXIT

./gradlew :androidApp:assembleDebug :androidTestFixture:assembleDebug

adb_cmd install -r -t androidApp/build/outputs/apk/debug/androidApp-debug.apk
adb_cmd install -r -t apps/android-test-fixture/app/build/outputs/apk/debug/androidTestFixture-debug.apk

wake_device
adb_cmd shell am force-stop "$APP_PACKAGE"
adb_cmd shell am force-stop "$FIXTURE_PACKAGE"
set_dive_deep_enabled_pref true
adb_cmd logcat -c
write_setting enabled_accessibility_services "$(append_service "$OLD_SERVICES")"
write_setting accessibility_enabled 1

adb_cmd shell am start -n "$FIXTURE_PACKAGE/.MainActivity" >/dev/null

for _ in $(seq 1 180); do
  adb_cmd logcat -d -s DiveDeepOverlay > /tmp/divedeep-overlay.log
  if grep -Eq 'render frame .* items=[1-9][0-9]*' /tmp/divedeep-overlay.log; then
    break
  fi
  sleep 1
done

cat /tmp/divedeep-overlay.log

if ! grep -Eq 'render frame .* items=[1-9][0-9]*' /tmp/divedeep-overlay.log; then
  adb_cmd logcat -d -s DiveDeepAccessibility
  echo "DiveDeep overlay did not render translated items." >&2
  exit 1
fi

if grep -Fq '[English]' /tmp/divedeep-overlay.log; then
  echo "DiveDeep overlay used mock translations." >&2
  exit 1
fi

echo "DiveDeep Android E2E passed."
