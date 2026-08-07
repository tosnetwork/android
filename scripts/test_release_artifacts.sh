#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

wallet_dir='apps/wallet/instance/main/build/outputs/apk/site/release'
signer_dir='apps/signer/build/outputs/apk/release'
wallet_apk="$wallet_dir/main-site-release-unsigned.apk"
signer_apk="$signer_dir/signer-release-unsigned.apk"
apk_listing="$(mktemp "${TMPDIR:-/tmp}/tos-wallet-apk.XXXXXX")"
trap 'rm -f "$apk_listing"' EXIT

fail() {
  echo "release-artifacts: $*" >&2
  exit 1
}

test -s "$wallet_apk" || fail "wallet Release APK is missing"
test -s "$signer_apk" || fail "signer Release APK is missing"
grep -Fq '"applicationId": "network.tos.wallet"' "$wallet_dir/output-metadata.json" \
  || fail "wallet Release application ID is wrong"
grep -Fq '"applicationId": "network.tos.signer"' "$signer_dir/output-metadata.json" \
  || fail "signer Release application ID is wrong"

unzip -Z1 "$wallet_apk" >"$apk_listing"
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
  grep -Fqx "lib/$abi/liblibsodium.so" "$apk_listing" \
    || fail "wallet APK lacks the Sodium JNI library for $abi"
done

echo "release-artifacts: PASS"
