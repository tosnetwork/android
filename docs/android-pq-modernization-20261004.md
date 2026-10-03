# Android TOS protocol modernization, 2026-10-04

The work starts from Android `8035a30caa1b3b9628c675d32fe2868711ee61cd`
and targets TOS `ee5ad71c343eb2227a8bd42d57bea259da4da0ec`.

## Resulting behavior

- New wallets use native TOS recovery salts and the frozen TOS V5R1 contract,
  whose code hash is
  `086a86aa9913c0ec52277adbb7e4b5695964dbb8c817ad0c305cdd345bbfac69`.
  The signature covers opcode, signed int32 global ID, plain uint32 subwallet
  number, expiry, sequence number and actions. First deployment also retains its
  requested expiry. Sender initialization belongs only to the external envelope;
  native outgoing messages do not borrow the sender's deployment state.
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
  Native reconciliation uses the sequence in the signed message, checks its
  wallet/network/subwallet binding, and checks the receipt before the first
  broadcast as well. A competing send during passcode entry cannot reset the
  retry baseline to a newer sequence.
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
The automation Gemfile requires Ruby3.3 or newer; local bundle validation used
Ruby3.4.6. The dependency-audit workflow uses Ruby3.3 to match the resolved gems.

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

This is an in-progress checkpoint. The complete Wallet device suite, final screen
configuration matrix and authenticated Signer/Release smoke checks are still pending.
Earlier bounded Wallet/core evidence is pinned to
`65e16c4e5bfb5674f68ba23916b3a77880b66a87`. A later first-send compatibility repair
changes the production Wallet, requiring a new frozen APK, full 45-method run,
unit run and affected Release/lint checks. These repeats are in progress.

- The earlier Wallet production Debug build and complete JVM run passed in
  `final-debug-unit-8.log`: 82 tests, no failures, errors or skips, including the
  real local-node mapper with explicit RPC/address environment inputs. The final
  Signer policy update and its two additional JVM tests passed in
  `signer-profile-final9.log`.
- After the signed-sequence and retired-worker fixes, the complete JVM run in
  `debug-fixture-ru-final13.log` completed 85 tests across 26 suites with no
  failures, errors or skips, including the real local-node mapper. That combined
  command subsequently failed when Android-test compilation found a missing
  mnemonic import in the repaired fixture. The import is fixed; the explicit
  four-APK rebuild in `debug-fixture-import-final14.log` passed in 3m4s.
  The complete 85-test/26-suite XML set is frozen separately; the later localnet
  harness repeats one mapper case and does not increase the unique unit count.
- `localnet-final65.log` passed the three-node replication, exact values, head
  convergence, controlled pagination setup and real-node mapper checks. Its mapper
  actually executed one test with no failures or skips.
- `release-lint-final65.log` passed both R8 Release builds and both full lint tasks
  in 25 minutes: Wallet has zero errors/five warnings and Signer zero errors/105
  warnings. `release-artifact-final65.log` passed both APK 16KB zip alignment,
  all 24 packaged 64-bit ELF checks and the Wallet permission boundary. NDK
  `llvm-readelf` is a symlink on both local/CI toolchains; the gate now discovers
  executable symlinks as well as regular files.
- Final14 Debug Wallet SHA256 is
  `06cd7e0130d4b9d4ffe21a2dbb7bfc75593690ddaa9c129d2136f84dd8a0ea0c`;
  its original instrumentation SHA256 is
  `7ee356784d771682863245958f91d871cbbc9b6dc35a4044a66dc62a71dc2409`.
  That pair passed 22 Wallet methods before the accessibility test observed no
  controls during RPC-dialog navigation. An unchanged-pair quiet retry failed
  earlier with `StaleObjectException` when clicking Settings. The actual RPC
  dialog XML contains its named input and all three named buttons. The test now
  reacquires stale nodes within a bounded deadline and waits for the actual RPC
  editor/Save button; accessibility assertions remain intact. Remaining execution
  uses the unchanged production APK with the rebuilt, separately hashed test APK.
- Six further methods passed with test15, including the repaired accessibility
  method and strict cold-launch/memory/navigation budgets. The subsequent receive
  QR test exposed a ZXing detection assumption: independent pixel reconstruction
  exactly matches all 37x37 encoded modules, but ordinary/TRY_HARDER detection
  misses this generated symbol. The axis-aligned bitmap fidelity check now uses
  PURE_BARCODE mode and still requires the exact native URI; a blank image must
  fail decoding. The same actual Android bitmap also passes the production MLKit
  QR scanner configuration, along with address copy/share assertions, on test16.
  Product QR/Wallet code is unchanged. The first test-only MLKit integration compile
  failed because the wrapper's CameraX supertype is not exported; the existing
  public MLKit API avoids a new dependency, and the corrected test build passed.
  Four further methods passed with test16. The first authenticated-send test then
  expected the bounceable input text while the actual confirmation correctly
  displayed the nonbounceable form for an uninitialized recipient. Both strings
  have valid CRC16 and decode to the same workchain0/32-byte destination; actual
  confirmation XML is retained. Test17 expects the existing normalized-address
  fixture and resumed before authentication or broadcast. These groups completed
  22 test14, six test15 and four test16 methods, with exact instrumentation hashes.
  They remain earlier-candidate evidence after the subsequent production repair.
- The actual first-send UI then failed before passcode authentication because
  `TransferEntity` inherited a path attaching the sender's StateInit to its
  outgoing recipient message at sequence zero. The native strict signer correctly
  rejected recipient deployment. Native outgoing initialization now contains only
  explicitly requested recipient state; sender deployment remains on the external
  envelope. Legacy selection behavior remains unchanged. Two new JVM regressions
  verify a complete native first-transfer message, strict parsing, exact amount/
  comment/destination, sender-only external deployment and legacy/explicit init
  selection. The first new test assertion compared StateInit object identity;
  it now compares canonical serialized cell hashes. Failed logs remain available.
  Final device acceptance repeats the entire suite on the repaired production APK.
- The updated full unit task has 87 passing results across 27 suites with zero
  failures, errors or skips, including the two first-deployment regressions. The
  27 XML reports are frozen in `final-unit87-results`. Wallet app/test APK packaging
  in `wallet-first-deploy-final18-fixed.log` remains in progress; the combined build
  is not reported as successful before it exits.
- The immutable final14 pair passed six native runtime checks on actual 16KB
  API36/API37 devices and all four existing non-payment UI boundaries on API37.
  API36 used compatibility=false and package compatibility disabled; API37 used
  bionic compatibility=fatal and package compatibility disabled. Initial zero-test
  startup ANRs, a SystemUI-overlay failure and a CPU-bound mnemonic timeout remain
  in the attempt record. The unchanged pair passed after guest/host load settled;
  no timeout or product edits were used for these repeats. The final compatibility
  manifest SHA256 is
  `cf420558f1342b41752ab8460a8b779ab4dfaab6c76334f7a5bac968cd005f05`.
- R8 unsigned Wallet SHA256 is
  `cf2f84db5dd69f303384ef6b81c123d44a155ea7f3408f221b2354cbbb486cd9`;
  unsigned Signer SHA256 is
  `2b4e147f6d7abe38b1774f0ca248d7cfe9c811d41b4ad9be1724f5d405a2c8a8`.
  Immutable originals and separately signed local-test copies are retained in
  `final-release65-apks`. Test signing uses the local Android debug certificate,
  is not a store signature, and leaves the original APKs untouched. Both copies
  passed signature verification and 16KB zip alignment; actual R8 UI smoke is pending.
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
- The frozen production Wallet APK completed the first 20 device methods before
  a stale test-only address prefix stopped the retained-wallet suite. The prefix,
  recovery-word assertions and zero-wallet generator now use the isolated native
  TOS fixture/domain. Already completed results remain separately pinned. A later
  signed-sequence concurrency repair changes the production Wallet APK, so final
  acceptance repeats the complete 45-method suite on a new frozen app/test pair.
- All four size/font/theme/locale configurations passed with Wallet production
  SHA747a7fad and instrumentation SHA65ba1af5. The final12 pair also passed the
  three native tests on API36 and API37 with actual 16KB pages and compatibility
  disabled, plus all four non-payment UI boundaries on API37. A first API36
  instrumentation startup hit an ART initialization ANR before any test ran;
  the unchanged APKs passed after the guest settled. Original failure logs remain
  available. The compatibility manifest SHA256 is
  `1ae6442a9bfc2d04cb723935620efe0f416ae7485bb0436dd1ef92e122babc14`.
- The first published CI lint run identified five missing Russian translations
  for the new Signer recovery/network text. All five are now translated, including
  the numeric network placeholder; default/Russian coverage is 78/78 keys. No lint
  suppression or baseline update was used. Final Signer artifacts and its three
  device tests are repeated after this resource repair.
- Full local lint then found an inherited APK-download worker posting notifications
  despite V1's deliberately restricted manifest. In-app update discovery was
  already disabled. Retained download callers now fail without scheduling work,
  and the historical worker class terminates queued pre-upgrade requests without
  downloading, posting notifications or starting a foreground service. The failed
  lint log is retained; final lint is repeated without a suppression or new
  permission.

The earlier production Wallet Debug APK SHA256 was
`747a7fad462920449fe62ba31be1407543acfa97abddbd248b77a72837bb2c45`.
The pre-translation-repair Signer Debug APK SHA256 was
`e4b62758ef0b28b10cf44ad04d36751812a1770d2f3ab02ad749114aef47fe3f`.
The instrumented APK hashes and final execution counts will accompany the
completed validation record.
