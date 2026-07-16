#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="${ADB:-adb}"
DEVICE="${DEVICE:-}"
APPIUM_HOST="${APPIUM_HOST:-127.0.0.1}"
APPIUM_PORT="${APPIUM_PORT:-4723}"
SERVICE="com.storyteller_f.divedeep/com.storyteller_f.divedeep.DiveDeepAccessibilityService"
APP_PACKAGE="com.storyteller_f.divedeep"
FIXTURE_PACKAGE="com.storyteller_f.divedeep.fixture"
LLMD_PACKAGE="com.storytellerf.llmd"
LLMD_SERVICE_CLASS="com.storytellerf.llmd.LlmdIpcService"
LLMD_APK="${LLMD_APK:-}"
LLMD_REPO="${LLMD_REPO:-}"
STARTED_APPIUM_PID=""
USING_EXISTING_APPIUM=false

adb_cmd() {
  if [[ -n "$DEVICE" ]]; then
    "$ADB" -s "$DEVICE" "$@"
  else
    "$ADB" "$@"
  fi
}

appium_status_url() {
  printf 'http://%s:%s/status' "$APPIUM_HOST" "$APPIUM_PORT"
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
  adb_cmd shell input swipe 540 1900 540 300 300 >/dev/null 2>&1 || true
}

is_llmd_package_installed() {
  adb_cmd shell pm path "$LLMD_PACKAGE" | tr -d '\r' | grep -q '^package:'
}

is_llmd_service_available() {
  local package_dump
  package_dump="$(adb_cmd shell dumpsys package "$LLMD_PACKAGE" | tr -d '\r')"
  grep -Fq 'com.storytellerf.llmd.action.BIND_IPC' <<<"$package_dump" &&
    { grep -Fq "$LLMD_SERVICE_CLASS" <<<"$package_dump" || grep -Fq "$LLMD_PACKAGE/.LlmdIpcService" <<<"$package_dump"; }
}

newest_apk() {
  local output_dir="$1"
  find "$output_dir" -type f -name '*.apk' -printf '%T@ %p\n' 2>/dev/null |
    sort -n |
    tail -n 1 |
    cut -d' ' -f2-
}

install_llmd_apk() {
  local apk="$1"
  if [[ ! -f "$apk" ]]; then
    echo "llmd APK does not exist: $apk" >&2
    exit 1
  fi

  echo "Installing llmd APK: $apk"
  adb_cmd install -r -t "$apk"
}

detect_llmd_repo() {
  if [[ -n "$LLMD_REPO" ]]; then
    printf '%s' "$LLMD_REPO"
  elif [[ -f "$ROOT_DIR/../llmd/app/package.json" ]]; then
    printf '%s' "$ROOT_DIR/../llmd"
  fi
}

build_and_install_llmd() {
  local repo="$1"
  local app_dir="$repo/app"
  local output_dir="$app_dir/src-tauri/gen/android/app/build/outputs/apk"
  local apk

  if [[ ! -f "$app_dir/package.json" ]]; then
    echo "LLMD_REPO does not point to an llmd checkout with app/package.json: $repo" >&2
    exit 1
  fi

  echo "Building llmd Android debug APK through Tauri CLI..."
  (
    cd "$app_dir"
    if [[ ! -d node_modules ]]; then
      npm ci
    fi
    npm run tauri android build -- --debug --apk
  )

  apk="$(newest_apk "$output_dir")"
  if [[ -z "$apk" ]]; then
    echo "Could not find a built llmd APK under: $output_dir" >&2
    exit 1
  fi
  install_llmd_apk "$apk"
}

ensure_llmd_service() {
  local repo
  if ! is_llmd_package_installed || ! is_llmd_service_available; then
    if [[ -n "$LLMD_APK" ]]; then
      install_llmd_apk "$LLMD_APK"
    else
      repo="$(detect_llmd_repo)"
      if [[ -n "$repo" ]]; then
        build_and_install_llmd "$repo"
      fi
    fi
  fi

  if ! is_llmd_package_installed; then
    echo "Local llmd package is not installed: $LLMD_PACKAGE" >&2
    echo "Set LLMD_APK to an existing debug APK or LLMD_REPO to an llmd checkout." >&2
    exit 1
  fi

  if ! is_llmd_service_available; then
    echo "Local llmd IPC service is unavailable: $LLMD_SERVICE_CLASS" >&2
    echo "Install an llmd build that includes the IPC authorization service." >&2
    exit 1
  fi
}

ensure_node_dependencies() {
  if [[ ! -d "$ROOT_DIR/node_modules" ]]; then
    npm ci
  fi
}

ensure_appium_driver() {
  local installed_drivers
  installed_drivers="$(npx appium driver list --installed 2>&1)"
  if ! grep -Fq 'uiautomator2' <<<"$installed_drivers"; then
    npx appium driver install uiautomator2
  fi
}

start_appium() {
  if curl -fsS "$(appium_status_url)" >/dev/null 2>&1; then
    USING_EXISTING_APPIUM=true
    return
  fi

  ensure_appium_driver
  npx appium --address "$APPIUM_HOST" --port "$APPIUM_PORT" --log-level error > /tmp/divedeep-appium.log 2>&1 &
  STARTED_APPIUM_PID="$!"
  for _ in $(seq 1 60); do
    if curl -fsS "$(appium_status_url)" >/dev/null 2>&1; then
      return
    fi
    sleep 1
  done

  cat /tmp/divedeep-appium.log >&2 || true
  echo "Appium server did not start." >&2
  exit 1
}

set_dive_deep_enabled() {
  APPIUM_HOST="$APPIUM_HOST" APPIUM_PORT="$APPIUM_PORT" DEVICE="$DEVICE" \
    node test/e2e/android-toggle.js "$1"
}

authorize_llmd_ipc() {
  APPIUM_HOST="$APPIUM_HOST" APPIUM_PORT="$APPIUM_PORT" DEVICE="$DEVICE" \
    node test/e2e/android-authorize-llmd.js
}

cleanup() {
  set +e
  set_dive_deep_enabled false >/dev/null 2>&1
  adb_cmd shell am force-stop "$APP_PACKAGE" >/dev/null 2>&1
  adb_cmd shell am force-stop "$FIXTURE_PACKAGE" >/dev/null 2>&1
  restore_setting enabled_accessibility_services "$OLD_SERVICES" >/dev/null 2>&1
  restore_setting accessibility_enabled "$OLD_ACCESSIBILITY_ENABLED" >/dev/null 2>&1
  if [[ -n "$STARTED_APPIUM_PID" && "$USING_EXISTING_APPIUM" == false ]]; then
    kill "$STARTED_APPIUM_PID" >/dev/null 2>&1
  fi
}

cd "$ROOT_DIR"

ensure_llmd_service

OLD_SERVICES="$(read_setting enabled_accessibility_services)"
OLD_ACCESSIBILITY_ENABLED="$(read_setting accessibility_enabled)"
trap cleanup EXIT

ensure_node_dependencies
./gradlew :androidApp:assembleDebug :androidTestFixture:assembleDebug

adb_cmd install -r -t androidApp/build/outputs/apk/debug/androidApp-debug.apk
adb_cmd install -r -t apps/android-test-fixture/app/build/outputs/apk/debug/androidTestFixture-debug.apk

wake_device
adb_cmd shell am force-stop "$APP_PACKAGE"
adb_cmd shell am force-stop "$FIXTURE_PACKAGE"
start_appium
authorize_llmd_ipc
set_dive_deep_enabled true
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
