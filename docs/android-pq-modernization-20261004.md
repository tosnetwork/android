# Android TOS protocol modernization, 2026-10-04

The work starts from Android `8035a30caa1b3b9628c675d32fe2868711ee61cd`
and targets TOS `ee5ad71c343eb2227a8bd42d57bea259da4da0ec`.

## Resulting behavior

- New wallets use native TOS recovery salts and the frozen TOS V5R1 contract,
  whose code hash is
  `086a86aa9913c0ec52277adbb7e4b5695964dbb8c817ad0c305cdd345bbfac69`.
  The signature covers opcode, signed int32 global ID, plain uint32 subwallet
  number, expiry, sequence number and actions. First deployment also retains its
  requested expiry.
- TOS network identity comes from Config19 and VM capabilities from Config8 at
  one masterchain height. New wallets persist their expected global ID. Native
  sends retain one node and credential for sequence, fee, balance, broadcast and
  retry reconciliation. Failed or malformed sequence lookups never become a
  deployment sequence of zero. Fee responses require all four nonnegative integral
  fields and reject sum overflow.
- An accepted-response timeout is reconciled against the exact submitted external
  message hash, including StateInit, and a successful compute/action receipt with
  real outgoing messages and no skipped actions. Another device advancing the same
  sequence cannot confirm this payment. An unproven receipt after a nonce advance
  remains an unknown result and stops automatic replay. Only the transaction
  manager controls replay, rechecking the receipt again after each retry delay.
- Wallet version `5` and legacy key derivation remain stable. SQLite upgrades from
  versions `1`, `2`, `3` and `4` cumulatively reach version `5`, adding a nullable
  network field. Native TOS version `6` is distinct and
  requires a network ID. Native and legacy mnemonic formats cannot be mixed in
  one repository import. Dual-valid recovery phrases offer explicit TOS and
  Legacy choices and produce independently verified, different keys and addresses.
- The standalone signer records native derivation metadata, preserves legacy CSV
  vault interpretation and checks the derived public key. TOS V5 requests need an
  explicit int32 network ID and sequence number. Malformed, expired, network-mismatched
  or undisplayed sweep/carry actions are rejected before signing. The native
  offline signer accepts only ordinary internal TOS transfers with empty bodies
  or complete UTF-8 comments. It rejects unknown payloads, extra currencies,
  recipient deployment, external messages, hidden message headers and trailing
  data. Confirmation displays all nine decimal places and the numeric TOS network
  ID; native audit export stays local and does not open TON emulation services.
  The encrypted vault also retains a native-key profile marker. A native key
  requires the strict native request profile before confirmation and again before
  authentication and signing. Native V5 bytes cannot bypass these checks by
  changing or omitting the URI version label. Ordinary legacy requests and CSV
  vaults remain usable with legacy keys.
- Onboarding exposes RPC settings and retry before a wallet exists, so an
  unavailable default endpoint does not prevent selecting a reachable node.
- Fastlane is resolved to `2.240.1` and rubyzip to `3.7.0`, satisfying the security
  fix for CVE-2026-85396 / GHSA-47m2-wp7j-p9vc (`rubyzip >=3.4.0`).
- Wallet installs identify as `1.1.0` / version code `3`; the standalone signer
  identifies as `0.3.0` / code `24`.
- The Sodium JNI wrapper links the existing static Sodium archive, and both it
  and the blur library use NDK27's two 16KB linker flags. CameraX is updated to
  the compatible stable `1.6.2`. Release gates inspect every arm64/x86_64 ELF LOAD,
  reject rounded RELRO protection that covers writable sections, and require
  16KB APK zip alignment. This follows the
  [official Android page-size guidance](https://developer.android.com/guide/practices/page-sizes).

## Authentication and deployment boundary

The current network uses PQ validator consensus while permitting Ed25519 user
wallets. This update keeps the native wallet's Ed25519 authentication explicit.
Capability discovery reports ML-DSA primitives from VM16 and experimental Falcon
from VM19; the tested VM18 network does not advertise Falcon support. Ordinary
native V5 creation and sending require VM6, the minimum established by independent
execution across VM0–18: VM0–3 rejected with exit6, VM4–5 with exit5, and VM6–18
computed and acted successfully with one real outgoing message. No PQ relayer
wallet UI, native PQ key migration, gasless service or network activation is added.

The isolated three-validator development network has global ID `3` and VM18.
No production network ID is inferred from it. Production endpoint DNS availability
is operational state, so onboarding permits entering a verified endpoint.

## Reproduction and test data

Use JDK17 and the configured Android SDK. Resource-constrained checks use
`--max-workers=2 -Dorg.gradle.parallel=false` with a 2GB Gradle heap. Logs for this
run are retained in `/tmp/android-pq-20261004`.

The tested toolchain is JDK17.0.20, Gradle8.14.3, Android Gradle Plugin8.12.3,
Kotlin2.2.20 and NDK27.0.12077973. Both applications compile and target API36;
Wallet supports API24 and newer, Signer API26 and newer. API36 meets the
[current Google Play target API requirement](https://developer.android.com/google/play/requirements/target-sdk)
starting 2026-08-31. No store publication is part of this work.

The cross-platform unit vectors verify code hash, address and unsigned signing
hash against independent TOS SDK and Python implementations. Native mnemonic,
legacy mnemonic and a real phrase valid in both domains are checked independently.
All phrases embedded in test sources are PUBLIC TEST DATA and must never hold real
assets. Android live UI uses a separate fixture with address
`0:6d189c87350930f05ab93e565e522e2c52dca9d4def5460606c6cb2a9013555a`,
isolating its nonce and balance from iOS tests.

`scripts/test_v1_localnet.sh` targets the explicitly configured local controller,
creates its own incoming history for pagination, checks replication on all three
validators, and enables the real-node JVM mapper test. Environment values are
Gradle test inputs, so an earlier skipped offline result is not reused.

Emulator harnesses build by default. A local run may reuse the already frozen
app/test APK pair with `TOS_EMULATOR_SKIP_BUILD=1` only when both explicit
`TOS_EMULATOR_APP_SHA256` and `TOS_EMULATOR_TEST_SHA256` values match the files;
this permits one Release/lint Gradle process alongside device instrumentation.

The frozen signer boundary fixture is independently constructed and VM-tested.
The truncated-jetton case sent a real 5 TOS transfer while the original signer
displayed an unknown action; StateInit and external-message cases also emitted
messages. The extra-currency fixture proves hidden request semantics; the VM's
sender lacked that currency, so its ignored send failure emitted no message.
All four are rejected before the repaired signer exposes confirmation or signs.

## Executed validation

This is an in-progress checkpoint. Release/lint, the complete Wallet device suite,
screen configuration matrix and final Signer device suite are still pending.

- Final Wallet production Debug build and the complete JVM run passed in
  `final-debug-unit-8.log`: 82 tests, no failures, errors or skips, including the
  real local-node mapper with explicit RPC/address environment inputs. The final
  Signer policy update and its two additional JVM tests passed in
  `signer-profile-final9.log`. The combined current XML results contain 84 tests
  across 26 suites, with no failures, errors or skips.
- The real SQLite migration preflight passed against the earlier native candidate:
  versions1/2/3/4 upgraded through SQLiteOpenHelper and retained legacy wallet
  version, address, contract code, public/private key and null network identity.
  The complete final-APK suite repeats this check; the preflight alone is not
  counted as final device acceptance.
- `bundle check`, the resolved Fastlane invocation and `bundle-audit check --update`
  passed after resolving the rubyzip security update. Shell syntax, Python
  compilation and `git diff --check` also passed.
- ELF inspection failed on the original 4KB JNI wrapper and passed after the
  static-Sodium/linker repairs. Independent API36/API37 16KB device checks passed
  the three native runtime tests on frozen candidates. Final artifact hashes and
  the full compatibility repeat are recorded after completion, separately from
  these candidate results.
- Initial device execution exposed test-only AlertDialog selectors expecting
  mixed-case button labels. Current Android renders these labels in uppercase.
  The tests now select RPC and recovery-format buttons by their semantic Android
  button IDs and compare displayed recovery labels without case sensitivity.
  These failures did not change the production Wallet APK.
- The first Signer instrumentation invocation could not load AndroidJUnitRunner
  because its new test APK lacked the runner dependency. The test configuration
  now uses the existing Espresso dependency that supplies the runner. Device
  harnesses also explicitly fail unless instrumentation reports the expected
  completed test count; a crashed process cannot produce a successful gate.

The frozen production Wallet Debug APK SHA256 is
`747a7fad462920449fe62ba31be1407543acfa97abddbd248b77a72837bb2c45`.
The frozen final Signer Debug APK SHA256 is
`e4b62758ef0b28b10cf44ad04d36751812a1770d2f3ab02ad749114aef47fe3f`.
The instrumented APK hashes and final execution counts will accompany the
completed validation record.
