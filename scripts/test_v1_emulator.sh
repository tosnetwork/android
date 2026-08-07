#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

adb_bin="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
test -x "$adb_bin" || { echo "v1-emulator: adb not found" >&2; exit 1; }
"$adb_bin" get-state >/dev/null 2>&1 || { echo "v1-emulator: no booted emulator" >&2; exit 1; }

./gradlew :apps:wallet:instance:main:assembleDefaultDebug :apps:wallet:instance:main:assembleDefaultDebugAndroidTest

app_apk='apps/wallet/instance/main/build/outputs/apk/default/debug/main-default-debug.apk'
test_apk='apps/wallet/instance/main/build/outputs/apk/androidTest/default/debug/main-default-debug-androidTest.apk'
"$adb_bin" install -r -t "$app_apk" >/dev/null
"$adb_bin" install -r -t "$test_apk" >/dev/null

test_class='network.tos.wallet.V1ProductUiTest'
python3 scripts/tos_rpc_fault_proxy.py &
fault_proxy_pid=$!
trap 'kill "$fault_proxy_pid" 2>/dev/null || true' EXIT
methods=(
  cleanLaunchUsesTosBrandAndOnlyV1EntryPoints
  createWalletOpensPasscodeAndCancelLeavesNoWallet
  validNativePhraseStartsImportAndInvalidPhrasesStayRejected
  nativeImportScreenIsTwentyFourWordsOnly
  invalidWordCountRemainsRejectedInUi
  unknownMnemonicWordRemainsRejectedInUi
  invalidMnemonicChecksumRemainsRejectedInUi
  cancelledImportLeavesNoWalletOrPasscode
  passcodeMismatchRetriesAndMatchingCodesCreateWallet
  deferredDeepLinksCannotOpenProductScreens
  backgroundAndForegroundDoNotExposeOrCrashOnboarding
  onboardingControlsExposeAccessibleNames
  sodiumSecretBoxRoundTripsOnAndroidAbi
  nativeTosFormattingCoversZeroFractionsAndMaximum
)

run_method() {
  local method="$1" clear_data="$2"
  "$adb_bin" shell am force-stop network.tos.wallet >/dev/null
  if [[ "$clear_data" == true ]]; then
    "$adb_bin" shell pm clear network.tos.wallet >/dev/null
  fi
  "$adb_bin" logcat -c
  output=$("$adb_bin" shell am instrument -w \
    -e class "${test_class}#${method}" \
    network.tos.wallet.test/androidx.test.runner.AndroidJUnitRunner)
  printf '%s\n' "$output"
  if [[ "$output" != *"OK (1 test)"* ]] || [[ "$output" == *"FAILURES!!!"* ]]; then
    echo "v1-emulator: FAILED (${method})" >&2
    exit 1
  fi
}

for method in "${methods[@]}"; do
  run_method "$method" true
done

persistent_methods=(
  deterministicFundedWalletFixtureReachesHomeAndPersists
  persistedWalletColdLaunchShowsExactNativeBalanceAndAddress
  walletWindowProtectsSensitiveContentAcrossBackgroundAndForeground
  retainedWalletControlsExposeAccessibleNames
  launchMemoryAndRepeatedNavigationStayWithinBudgets
  rpcSettingValidatesPersistsAndRoutesToSecondLocalValidator
  persistedRpcSettingSurvivesColdProcessAndResets
  localTransferRefreshesNativeBalance
  offlineHistoryShowsErrorAndRetryReconnects
  receiveCopiesSharesAndEncodesExactNativeTosAddress
  walletSendAndSettingsExposeOnlyNativeV1Controls
  sendValidationConfirmationAndCancelDoNotBroadcast
  nativeTransferSignsBroadcastsAndRoundTripsUnicodeComment
  nativeHistoryDetailsShowExactChainFields
  timeoutRetryBroadcastsOnlyOnceAndRelaunchReconcilesHistory
  maxNativeTransferCarriesBalanceWhileReservingNetworkFee
  fundedHistoryLoadsAndPaginatesWithoutDuplicateTransactions
  passcodeThrottleKeystoreAndRuntimeSecretPolicyHold
  recoveryPhraseRequiresCorrectPasscode
  signOutRequiresConfirmationAndReturnsToCleanOnboarding
  unfundedGeneratedWalletRendersZeroBalanceAndEmptyHistory
)
run_method "${persistent_methods[0]}" true
for method in "${persistent_methods[@]:1}"; do
  run_method "$method" false
done

echo "v1-emulator: PASS ($((${#methods[@]} + ${#persistent_methods[@]})) scenarios)"
