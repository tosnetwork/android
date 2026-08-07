#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

fail() {
  echo "v1-static: $*" >&2
  exit 1
}

flags='apps/wallet/api/src/main/java/network/tos/wallet/api/entity/FlagsEntity.kt'
for flag in disableSwap disableExchangeMethods disableDApps disableStaking disableTron disableBattery disableGasless disableUsde disableNativeSwap disableOnboardingStory disableNfts; do
  grep -Eq "${flag}[[:space:]]*=[[:space:]]*true" "$flags" || fail "$flag is not disabled in the offline V1 defaults"
done
for key in disable_swap disable_exchange_methods disable_dapps disable_staking disable_tron disable_battery disable_gaseless disable_usde disable_native_swap disable_onboarding_story disable_nfts; do
  grep -Eq "optBoolean\(\"${key}\",[[:space:]]*true\)" "$flags" || fail "$key does not fail closed when remote configuration omits it"
done

policy='apps/wallet/instance/app/src/main/java/network/tos/wallet/app/deeplink/DeepLinkFeaturePolicy.kt'
dispatcher='apps/wallet/instance/app/src/main/java/network/tos/wallet/app/ui/screen/root/RootViewModel.kt'
for flag in disableSwap disableExchangeMethods disableDApps disableStaking disableBattery disableNfts; do
  grep -Fq "flags.$flag" "$policy" || fail "$flag is not consumed by the deep-link feature policy"
done
grep -Fq 'is DeepLinkRoute.Tabs.Browser -> !flags.disableDApps' "$policy" \
  || fail "DApp browser deep links do not honor disableDApps"
grep -Fq 'DeepLinkFeaturePolicy.isAllowed(route, api.config.flags)' "$dispatcher" \
  || fail "root deep-link dispatcher does not enforce the feature policy"
test "$(grep -Fc 'api.config.flags.disableDApps' "$dispatcher")" -ge 2 \
  || fail "DApp shortcut and push entry points are not both gated"

constants='apps/wallet/api/src/main/java/network/tos/wallet/api/Constants.kt'
grep -Eq 'https?://[^"[:space:]]*tos\.network' "$constants" || fail "no TOS-owned API endpoint is configured"

manifest='apps/wallet/instance/app/src/main/AndroidManifest.xml'
grep -Fq '<data android:scheme="tos"' "$manifest" || fail "tos:// is not registered"
if grep -Fq '<data android:scheme="tonkeeper"' "$manifest"; then
  fail "unreleased wallet registers the legacy Tonkeeper scheme"
fi

if grep -R -I -n -E 'git@github\.com:tonkeeper/|github\.com/tonkeeper/[^ )"[:space:]]+\.git' .github/workflows; then
  fail "workflow pushes to or checks out an upstream Tonkeeper repository"
fi

echo "v1-static: PASS"
