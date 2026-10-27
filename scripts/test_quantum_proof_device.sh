#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
adb_bin="${ADB_BIN:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}/platform-tools/adb}"
test -x "$adb_bin" || { echo 'quantum-proof-device: adb not found' >&2; exit 1; }
"$adb_bin" get-state >/dev/null
apk='lib/security/build/outputs/apk/androidTest/debug/security-debug-androidTest.apk'
test -f "$apk"
"$adb_bin" install -r -t "$apk" >/dev/null
classes='network.tos.security.pq.QuantumWalletTransactionDeviceTest,network.tos.security.pq.QuantumProofDeviceTest,network.tos.security.pq.QuantumProofSessionDeviceTest'
mkdir -p build/quantum-proof-device
if output=$("$adb_bin" shell am instrument -w -e class "$classes" \
  network.tos.security.test/androidx.test.runner.AndroidJUnitRunner 2>&1); then
  status=0
else
  status=$?
fi
printf '%s\n' "$output" | tee build/quantum-proof-device/instrumentation.log
# Instrumentation can exit0 after a crash or a zero-test run. Require the whole
# selected suite:4 transaction/JNI tests and3 existing proof/session tests.
if [[ "$status" != 0 ]] || [[ "$output" != *'OK (7 tests)'* ]] || [[ "$output" == *'FAILURES!!!'* ]]; then
  echo 'quantum-proof-device: FAILED (expected all7 tests)' >&2
  exit 1
fi
echo 'quantum-proof-device: PASS (7 tests)'
