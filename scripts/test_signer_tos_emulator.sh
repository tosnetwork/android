#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
export ANDROID_SERIAL="${ANDROID_SERIAL:-emulator-5554}"
adb_bin="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
"$adb_bin" get-state >/dev/null
if [[ "${TOS_EMULATOR_SKIP_BUILD:-0}" != 1 ]]; then
  ./gradlew --no-daemon --max-workers=2 -Dorg.gradle.parallel=false \
    '-Dorg.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8' \
    :apps:signer:assembleDebug :apps:signer:assembleDebugAndroidTest
else
  python3 scripts/check_prebuilt_apks.py apps/signer/build/outputs/apk/debug/signer-debug.apk apps/signer/build/outputs/apk/androidTest/debug/signer-debug-androidTest.apk
fi
"$adb_bin" install -r -t apps/signer/build/outputs/apk/debug/signer-debug.apk >/dev/null
"$adb_bin" install -r -t apps/signer/build/outputs/apk/androidTest/debug/signer-debug-androidTest.apk >/dev/null
"$adb_bin" shell pm clear network.tos.signer.debug >/dev/null
output=$("$adb_bin" shell am instrument -w -e class network.tos.signer.SignerTosUiTest \
  network.tos.signer.debug.test/androidx.test.runner.AndroidJUnitRunner)
printf '%s\n' "$output"
if [[ "$output" != *"OK (3 tests)"* ]] || [[ "$output" == *"FAILURES!!!"* ]]; then
  echo 'signer-tos-emulator: FAILED (instrumentation did not pass all 3 tests)' >&2
  exit 1
fi
echo 'signer-tos-emulator: PASS (3 native request, export, and vault scenarios)'
