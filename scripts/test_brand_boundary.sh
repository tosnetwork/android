#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
scan_output="$(mktemp "${TMPDIR:-/tmp}/tos-android-brand-boundary.XXXXXX")"
trap 'rm -f "$scan_output"' EXIT

fail() {
  echo "brand-boundary: $*" >&2
  exit 1
}

grep -Fq 'fun namespacePrefix(name: String) = "network.tos.$name"' buildSrc/src/main/kotlin/Build.kt \
  || fail "first-party namespace prefix is not network.tos"
grep -Fq 'applicationId = "network.tos.wallet"' apps/wallet/instance/main/build.gradle.kts \
  || fail "wallet application ID changed"
grep -Fq 'namespace = Build.namespacePrefix("wallet.app")' apps/wallet/instance/app/build.gradle.kts \
  || fail "wallet implementation namespace changed"
grep -Fq 'applicationId = "network.tos.signer"' apps/signer/build.gradle.kts \
  || fail "signer application ID is not TOS-native"

if find apps kmp lib ui -type d \( -path '*/src/*/com/tonapps*' -o -path '*/src/*/com/tonkeeper*' \) -print -quit | grep -q .; then
  fail "legacy first-party source directory remains"
fi

if find apps kmp lib ui buildSrc buildLogic \
    \( -type d \( -name build -o -name .gradle -o -name .cxx \) -prune \) -o \
    \( -type f \( -name '*.kt' -o -name '*.java' \) -print0 \) \
    | xargs -0 grep -I -n -E '^(package|import) com\.(tonapps|tonkeeper)(\.|$)' >"$scan_output"; then
  cat "$scan_output" >&2
  fail "legacy first-party package/import remains"
fi

grep -Fq 'Java_network_tos_security_Sodium_init' lib/security/src/main/cpp/sodium_lib.cpp \
  || fail "JNI symbols do not match network.tos.security.Sodium"
if grep -Fq 'Java_com_tonapps_security_Sodium_' lib/security/src/main/cpp/sodium_lib.cpp; then
  fail "legacy Sodium JNI symbol remains"
fi

grep -Fq 'private const val PREFIX = "tos://"' apps/wallet/instance/app/src/main/java/network/tos/wallet/app/deeplink/DeepLinkRoute.kt \
  || fail "TOS deep-link prefix is missing"
if grep -R -I -n -E 'com\.tonapps|_com_tonapps|tonkeeper://' apps --exclude-dir=build; then
  fail "unreleased product still contains a legacy Tonkeeper identity"
fi

if grep -R -I -n -i 'tonkeeper' .github/workflows; then
  fail "workflow contains upstream Tonkeeper branding or destinations"
fi

grep -Fq 'derived from the open-source Tonkeeper Android wallet' NOTICE \
  || fail "upstream attribution is missing"
grep -Fq 'Inherited Protocol Boundary' docs/inherited-protocol-boundary.md \
  || fail "inherited protocol boundary documentation is missing"

warm_tos_icon_sha="92d9942ce1511cd08235d1aa9fd0eefa5b61bc4548a6cf2f2f5bb3e2ac7db1d1"
for icon in \
  apps/wallet/api/src/main/res/drawable/ic_ton_with_bg.png \
  apps/wallet/api/src/main/res/drawable/ic_ton_logo.png \
  apps/wallet/instance/app/src/main/res/drawable-nodpi/ic_ton.png; do
  actual_sha="$(shasum -a 256 "$icon" | awk '{print $1}')"
  [[ "$actual_sha" == "$warm_tos_icon_sha" ]] \
    || fail "$icon is not the approved yellow-and-white TOS token asset"
done

small_warm_tos_icon_sha="99cb4216dca0246d30505dc5e5135fb5c3ca6a19b4361a2ab8d223e441908d5b"
actual_sha="$(shasum -a 256 apps/wallet/instance/app/src/main/res/drawable-nodpi/ic_ton_28.png | awk '{print $1}')"
[[ "$actual_sha" == "$small_warm_tos_icon_sha" ]] \
  || fail "ic_ton_28.png is not the approved yellow-and-white TOS token asset"

echo "brand-boundary: PASS"
