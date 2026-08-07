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

constants='apps/wallet/api/src/main/java/network/tos/wallet/api/Constants.kt'
grep -Eq 'https?://[^"[:space:]]*tos\.network' "$constants" || fail "no TOS-owned API endpoint is configured"

manifest='apps/wallet/instance/app/src/main/AndroidManifest.xml'
grep -Fq '<data android:scheme="tos"' "$manifest" || fail "tos:// is not registered"
grep -Fq '<data android:scheme="tonkeeper"' "$manifest" || fail "legacy inbound link support is not registered"

if grep -R -I -n -E 'git@github\.com:tonkeeper/|github\.com/tonkeeper/[^ )"[:space:]]+\.git' .github/workflows; then
  fail "workflow pushes to or checks out an upstream Tonkeeper repository"
fi

echo "v1-static: PASS"
