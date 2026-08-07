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
grep -Fq 'applicationId = "com.tonapps.signer"' apps/signer/build.gradle.kts \
  || fail "published signer application ID compatibility was removed"

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
grep -Fq 'LEGACY_TONKEEPER_PREFIX = "tonkeeper://"' apps/wallet/instance/app/src/main/java/network/tos/wallet/app/deeplink/DeepLinkRoute.kt \
  || fail "legacy inbound deep-link compatibility is missing"

if grep -R -I -n -i 'tonkeeper' .github/workflows; then
  fail "workflow contains upstream Tonkeeper branding or destinations"
fi

grep -Fq 'derived from the open-source Tonkeeper Android wallet' NOTICE \
  || fail "upstream attribution is missing"
grep -Fq 'Legacy TON Compatibility Boundary' docs/legacy-ton-compatibility-boundary.md \
  || fail "compatibility boundary documentation is missing"

echo "brand-boundary: PASS"
