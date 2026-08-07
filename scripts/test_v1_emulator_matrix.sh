#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
adb_bin="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
app_id='network.tos.wallet'
test_class='network.tos.wallet.V1ProductUiTest#configuredOnboardingDoesNotClipOrCrash'

restore() {
  "$adb_bin" shell wm size reset >/dev/null
  "$adb_bin" shell wm density reset >/dev/null
  "$adb_bin" shell settings put system font_scale 1.0
  "$adb_bin" shell cmd uimode night no >/dev/null
  "$adb_bin" shell cmd locale set-app-locales "$app_id" --locales en-US >/dev/null
}
trap restore EXIT

./gradlew :apps:wallet:instance:main:assembleDefaultDebug :apps:wallet:instance:main:assembleDefaultDebugAndroidTest
"$adb_bin" install -r -t apps/wallet/instance/main/build/outputs/apk/default/debug/main-default-debug.apk >/dev/null
"$adb_bin" install -r -t apps/wallet/instance/main/build/outputs/apk/androidTest/default/debug/main-default-debug-androidTest.apk >/dev/null

run_config() {
  local size="$1" font="$2" night="$3" locale="$4"
  "$adb_bin" shell wm size "$size" >/dev/null
  "$adb_bin" shell settings put system font_scale "$font"
  "$adb_bin" shell cmd uimode night "$night" >/dev/null
  "$adb_bin" shell cmd locale set-app-locales "$app_id" --locales "$locale" >/dev/null
  "$adb_bin" shell pm clear "$app_id" >/dev/null
  output=$("$adb_bin" shell am instrument -w -e class "$test_class" \
    "$app_id.test/androidx.test.runner.AndroidJUnitRunner")
  printf '%s\n' "$output"
  [[ "$output" == *"OK (1 test)"* ]] && [[ "$output" != *"FAILURES!!!"* ]]
}

run_config 720x1280 1.0 no en-US
run_config 720x1280 1.3 yes ja-JP
run_config 1080x2400 1.0 yes de-DE
run_config 1440x2560 1.3 no en-US

echo 'v1-emulator-matrix: PASS (4 size/font/theme/locale configurations)'
