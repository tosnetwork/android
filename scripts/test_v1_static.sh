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
for permission in POST_NOTIFICATIONS CAMERA NFC BLUETOOTH BLUETOOTH_ADMIN ACCESS_COARSE_LOCATION ACCESS_FINE_LOCATION BLUETOOTH_CONNECT BLUETOOTH_SCAN ACCESS_WIFI_STATE FOREGROUND_SERVICE FOREGROUND_SERVICE_DATA_SYNC REQUEST_INSTALL_PACKAGES READ_EXTERNAL_STORAGE WRITE_EXTERNAL_STORAGE WAKE_LOCK RECEIVE_BOOT_COMPLETED; do
  grep -Eq "android.permission.${permission}[^>]*tools:node=\"remove\"" "$manifest" \
    || fail "deferred permission $permission is not removed from the merged V1 manifest"
done
for permission in com.android.vending.BILLING com.google.android.finsky.permission.BIND_GET_INSTALL_REFERRER_SERVICE com.android.launcher.permission.INSTALL_SHORTCUT com.android.launcher.permission.UNINSTALL_SHORTCUT; do
  grep -F "android:name=\"${permission}\"" "$manifest" | grep -Fq 'tools:node="remove"' \
    || fail "deferred permission $permission is not removed from the merged V1 manifest"
done

qr_screen='apps/wallet/instance/app/src/main/java/network/tos/wallet/app/ui/screen/qr/QRScreen.kt'
grep -Fq '"tos://transfer/${address}"' "$qr_screen" || fail "receive QR does not emit the TOS payment scheme"
if grep -R -I -n -F '"ton://transfer/' apps/wallet/instance/app/src/main/java; then
  fail "first-party wallet code still emits a TON payment link"
fi

settings_vm='apps/wallet/instance/app/src/main/java/network/tos/wallet/app/ui/screen/settings/main/SettingsViewModel.kt'
for deferred_item in 'Item.SearchEngine(' 'Item.ConnectedApps(' 'Item.InstalledExtensions(' 'Item.Widget(' 'Item.W5(' 'Item.V4R2('; do
  if grep -Fq "uiItems.add(${deferred_item}" "$settings_vm"; then
    echo "v1-static: deferred settings entry is reachable: ${deferred_item}" >&2
    exit 1
  fi
done

grep -Fq 'window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)' \
  apps/wallet/instance/app/src/main/java/network/tos/wallet/app/ui/screen/root/RootActivity.kt || {
  echo 'v1-static: wallet window must protect sensitive content with FLAG_SECURE' >&2
  exit 1
}

bottom_tabs='apps/wallet/instance/app/src/main/res/menu/bottom_tabs.xml'
grep -Fq 'android:id="@+id/activity"' "$bottom_tabs" || {
  echo 'v1-static: native TOS History tab is missing' >&2
  exit 1
}
for deferred_tab in browser collectibles; do
  if grep -Fq "android:id=\"@+id/${deferred_tab}\"" "$bottom_tabs"; then
    echo "v1-static: deferred bottom tab is reachable: ${deferred_tab}" >&2
    exit 1
  fi
done

if grep -R -I -n -E 'git@github\.com:tonkeeper/|github\.com/tonkeeper/[^ )"[:space:]]+\.git' .github/workflows; then
  fail "workflow pushes to or checks out an upstream Tonkeeper repository"
fi

echo "v1-static: PASS"
