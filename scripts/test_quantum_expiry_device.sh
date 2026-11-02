#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
adb_bin="${ADB_BIN:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}/platform-tools/adb}"
test -x "$adb_bin"
"$adb_bin" get-state >/dev/null
apk='apps/wallet/data/account/build/outputs/apk/androidTest/debug/account-debug-androidTest.apk'
test -f "$apk"
"$adb_bin" install -r -t "$apk" >/dev/null
mkdir -p build/quantum-expiry-device
classes='network.tos.wallet.data.account.QuantumExpiryCapabilityTest,network.tos.wallet.data.account.QuantumPaymentOutboxTest'
status=0
"$adb_bin" shell am instrument -w -r -e class "$classes" \
  network.tos.wallet.data.account.test/androidx.test.runner.AndroidJUnitRunner \
  >build/quantum-expiry-device/instrumentation.log 2>&1 || status=$?
cat build/quantum-expiry-device/instrumentation.log
test "$status" = 0
python3 scripts/check_quantum_expiry_device.py build/quantum-expiry-device/instrumentation.log
