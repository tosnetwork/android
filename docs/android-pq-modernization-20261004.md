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
- TOS network identity comes from Config 19 and VM capabilities from Config 8 at
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
  changing or omitting the URI version label. An authenticated request without
  a callback uses the existing signature QR output. Ordinary legacy requests and
  CSV vaults remain usable with legacy keys.
- Onboarding exposes RPC settings and retry before a wallet exists, so an
  unavailable default endpoint does not prevent selecting a reachable node.
- Fastlane is resolved to `2.240.1` and rubyzip to `3.7.0`, satisfying the security
  fix for CVE-2026-85396 / GHSA-47m2-wp7j-p9vc (`rubyzip >=3.4.0`).
- Wallet installs identify as `1.1.0` / version code `3`; the standalone signer
  identifies as `0.3.0` / code `24`.
- The Sodium JNI wrapper links the existing static Sodium archive, and both it
  and the blur library use NDK 27's two 16 KB linker flags. CameraX is updated to
  the compatible stable `1.6.2`. Release gates inspect every arm64/x86_64 ELF LOAD,
  reject rounded RELRO protection that covers writable sections, and require
  16 KB APK zip alignment. This follows the
  [official Android page-size guidance](https://developer.android.com/guide/practices/page-sizes).

## Authentication and deployment boundary

The current network uses PQ validator consensus while permitting Ed25519 user
wallets. This update keeps the native wallet's Ed25519 authentication explicit.
Capability discovery reports ML-DSA primitives from VM 16 and experimental Falcon
from VM 19; the tested VM 18 network does not advertise Falcon support. Ordinary
native V5 creation and sending require VM 6, the minimum established by independent
execution across VM 0–18: VM 0–3 rejected with exit 6, VM 4–5 with exit 5, and VM 6–18
computed and acted successfully with one real outgoing message. No PQ relayer
wallet UI, native PQ key migration, gasless service or network activation is added.

The isolated three-validator development network has global ID `3` and VM 18.
No production network ID is inferred from it. Production endpoint DNS availability
is operational state, so onboarding permits entering a verified endpoint.

## Reproduction and test data

Use JDK 17 and the configured Android SDK. Resource-constrained checks use
`--max-workers=2 -Dorg.gradle.parallel=false` with a 2 GB Gradle heap. Logs for this
run are retained in `/tmp/android-pq-20261004`.

The tested toolchain is JDK 17.0.20, Gradle 8.14.3, Android Gradle Plugin 8.12.3,
Kotlin 2.2.20 and NDK 27.0.12077973. Both applications compile and target API 36;
Wallet supports API 24 and newer, Signer API 26 and newer. API 36 meets the
[current Google Play target API requirement](https://developer.android.com/google/play/requirements/target-sdk)
starting 2026-08-31. No store publication is part of this work.
The automation Gemfile requires Ruby 3.3 or newer; local bundle validation used
Ruby 3.4.6. The dependency-audit workflow uses Ruby 3.3 to match the resolved gems.

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

Local validation is complete for source/test head
`3d776b55835cca8b3cd37cabd79b6e03f4067372`. Wallet and shared production code
are unchanged from `dac22965299f45080701b14cdb535e2788be67dd`. Signer production
is pinned to tree `0c66db608b69c5e8244c09694fc28871cc082f13`, with build-script
blob `3563c234fe6ddf22abf539dc100aca4a9c71c91a`; its later UI harness repairs do
not change the application APK. Evidence is bound to the immutable files below.

| Completed check | Result | Evidence |
| --- | --- | --- |
| JVM results | 87 unique tests, 27 suites; zero failures, errors or skips | `final-combined-unit87-results/manifest.json` |
| Full Wallet device suite | 45 scenarios: 42 UI methods and three native methods | `wallet-ui-final18.result.json` |
| Wallet screen/font/theme/locale matrix | Four configurations passed | `wallet-matrix-final18.result.json` |
| Signer device suite | Four methods passed in 80.131s; zero failures or skips | `signer-ui-final21.result.json` |
| Actual 16 KB kernels | API 36 three native tests, API 37 three native tests and API 37 four existing UI tests passed | `api36-final-wallet18.result.json`, `api37-final-wallet18.result.json`, `api37-final-wallet18-existing-ui.result.json` |
| R8 builds, full lint and unsigned-artifact gates | Both applications passed; Wallet lint zero errors/five warnings, Signer zero errors/105 warnings | `wallet-release-lint-final18.log`, `signer-no-return-final19-build.log`, `release-artifact-final19.log` |
| Actual Wallet R8 smoke | Native restore, live balance, explicit lock, new wallet and cold persistence passed | `wallet18-release-native-{restore,create}.result.json`, `wallet18-release-lock.result.json` |
| Actual Signer R8 smoke | Native import, cold password unlock, authenticated no-return QR view and independently verified browser callback passed | `signer19-release-native-signing.result.json` |

Repeated preflights, screen configurations and API 37 reruns are separate
executions of existing methods. They do not inflate the 87 unique JVM or 45
Wallet-method totals. All actual 16 KB results above use the final Wallet 18 pair;
the full Signer device run uses the final Signer 21 test APK.

### Frozen APK provenance

Paths are beneath `/tmp/android-pq-20261004`. Original unsigned Release files
remain untouched; separately signed copies are used only for local QA.

| Artifact | SHA256 |
| --- | --- |
| `final-wallet18-apks/wallet.apk` | `86d142020f4a0050c11efd7d6e516a3e5bbc1b5efda4fcb409dffe882b795973` |
| `final-wallet18-apks/wallet-test.apk` | `32ef5c7fa332f34d92ec35b74e264b15c328365e12fdeee2419ea3e1ff88d5bd` |
| `final-signer21-ui-apks/signer.apk` | `e9f9973dd8594a98ae01935e0a68e5deb3abfb2c65c9e920dce126826dced160` |
| `final-signer21-ui-apks/signer-test.apk` | `bb9668913fde277595bada33982d045266633a8f326a8a4e3dbaa84572cacf77` |
| `final-release18-apks/wallet-release-unsigned.apk` | `a6a7fde21e310f96288c6eec4a39a14de121e626e41f4fdc9811c4301467e1f6` |
| `final-release18-apks/wallet-release-local-test-signed.apk` | `f75f3e7e09512799fc1bd287d77b3dce2710524ee6b2172b53e1f57737cd7a5a` |
| `final-signer19-apks/signer-release-unsigned.apk` | `eb848613247b9b2b99a00298ed81ca4abb2483f2e1564d66ca5b0c8adaf0e552` |
| `final-signer19-apks/signer-release-local-test-signed.apk` | `a6f6b178e2a77c644639ef1d05973208581ec121c244cea4fe50cc1b72cbdcaf` |

The Wallet 18 Debug manifest is
`c6aa8b3e590829d7c511ab489e8a54de074ffbffa5c78a82988a11402c193d11`;
the Signer 21 device manifest is
`1b6137fc61c381aa4f0dda98e5a6d32a741dea24fab096cc0a8f14efb9733cb6`.
The Signer 19 build manifest, including its fresh unit XML, is
`7b0924eb98fa784b88f150c784fb65f3b4f20d6ffd7503c26352ab5f5f0d1155`.

### JVM, live-chain and device checks

The explicit Wallet app/test packaging and full requested unit task passed in
6m28s. The subsequent affected Signer unit, Debug/test packaging, R8 Release and
full lint tasks passed in 2m27s: 58 tasks executed and 849 up-to-date. The combined
result manifest retains 85 unchanged Wallet/core results and replaces the two
Signer results with their fresh execution. Its unique total is 87, rather than
89, across 27 actual XML suites. The manifest SHA256 is
`6a2d53b87959fb51f55764ba1f7e126fe0a7eaa510d6d21fcb041a39e27205da`.
The real local-node mapper executed with explicit RPC/address inputs; the
localnet harness's later single-case repeat is not an additional unique test.

The complete Wallet run passed on the owned API 36.1/4 KB emulator using one
uniform app/test pair. It covers actual SQLite upgrades from versions 1/2/3/4
to 5, stable legacy code/address/keys and null network identity, explicit dual-domain
recovery and persistence, unavailable-node onboarding, RPC editing, accessibility,
address copy/share/QR decoding, authenticated transfers, exact Unicode history,
competing-device sequence handling, lost-response reconciliation, Max, pagination,
passcode/keystore controls and sign-out. No earlier partial groups are used to
reach 45. Its manifest SHA256 is
`42f6586a7e17afa8e730491d4d7daed63e324acfd940eb3b0aa2b9283fd7c018`.

A separate first-deployment preflight began with an uninitialized sender and
ended with an active account at sequence 1. The receipt has successful compute
and action phases, one real outgoing message and zero skipped actions. The
recipient received exactly 0.01 TOS and the raw transaction contains the exact
UTF-8 comment `TOS V1 测试 🌌`. Authentication, endpoint editing while confirmation
was open, and chain-backed history were checked. This 26.892s preflight repeats
one full-suite method. `native-first-send-final18.result.json` has SHA256
`edd37a2c7cebdf6947bc07e05f4e0cbeb01bb4e4799719923f0a16e60f851d80`.
An independent read-only audit matched original state, receipt fields, APK bytes,
unit XML and the preflight log; its SHA256 is
`dc766bbf2ccfa803b9e3f4faec5d78482a4af2557588ef597c71ca807799b812`.

The final screen matrix passed 720x1280/font 1/light/en-US,
720x1280/font 1.3/dark/ja-JP, 1080x2400/font 1/dark/de-DE and
1440x2560/font 1.3/light/en-US. Its manifest SHA256 is
`4aab5080efc10e0793cec085099f01039d950fb926bcc847e68e6e25011b5d20`.

The four final Signer methods include an authenticated no-return signature QR,
its actual decode and comparison with a valid 64-byte Ed25519 signature, strict
native confirmation/export, malformed or relabelled request rejection, and
native/legacy vault recovery. The full run's manifest SHA256 is
`69322ff74bbddb394fb4fa0e37f9beeb30a4f1569b639bc695537ec61eca2c82`.
A separate actual Debug QR was decoded using zxing-cpp 3.1.1 and checked with
PyNaCl 1.6.2; mutated signatures and body hashes were rejected. These are offline
signing checks with public test data, with no blockchain broadcast.

### Actual 16 KB runtime

Final Wallet 18 native checks passed on ARM64 Android 16/API 36 and
Android 17/API 37 guests reporting `PAGE_SIZE=16384`. API 36 used
`bionic.linker.16kb.app_compat.enabled=false`; API 37 used `fatal`.
Both reported `pm.16kb.app_compat.disabled=true`. All six packaged ARM64 libraries
loaded: graphics-path, barhopper, image-processing, Sodium, RenderScript toolkit
and surface utilities. The tests also executed Sodium encryption/decryption,
wrong-key and tamper rejection, and the JNI blur impulse/pixel assertions.
Loading the graphics-path and camera libraries does not claim full camera or
path-iteration testing.

The same final pair also passed the four existing non-payment methods on API 37:
node replacement before wallet creation, actual old-database migration and
explicit TOS/Legacy restoration of the dual-valid phrase with persisted keys.
The final result manifests are:

- API 36 three native tests: `55d0a5c1e38b7aedc6c1944c033381336e8332605f3ec1fb6bc4a1ea8db89cc4`.
- API 37 three native tests: `b7086789c9abe63a3434fd55fb13f8f932bfc35b57813d94b90de7f510435906`.
- API 37 four existing UI tests: `a0591667d7849e6b8351028cbbc42fb9216ad3ac119302afbe4fee2ae90905b4`.

These ten executions completed with no failed or skipped tests. An initial host
launch pointed at the wrong SDK and failed before guest boot; its missing-image
log is retained separately and is not an instrumentation result.

### R8 Release runtime and artifact gates

The affected Wallet R8 build and full lint task passed in 19m28s, with 43 executed
and 1571 up-to-date tasks. Both final unsigned Release APKs passed 16 KB zip
alignment, all 24 packaged arm64/x86_64 ELF checks and the Wallet permission
allowlist. Executable NDK `llvm-readelf` symlinks are discovered on local and CI
toolchains. The gate log SHA256 is
`65d3de8d36098b08423e1ca4cc6755c6e83d45f1ca7b3e75b8af2014f1b568d1`.
Both separate QA-signed copies passed certificate verification and 16 KB zip
alignment. Their certificate is a local test certificate, distinct from the AGP
Debug APK certificate. Device smoke uses clean installs and does not establish a
signed Debug-to-Release upgrade or distribution-signing acceptance.

The actual QA-signed Wallet 18 R8 APK completed onboarding RPC editing to
`http://10.0.2.2:18545`, public native 24-word restoration, PIN creation, the exact
fixture address `UQBtGJyHNQkw8Fq5PlZeUi4sUtyp1N71RgYGxssqkBNVWlJ3` and live 100 TOS.
Force-stop/cold launch in 804 ms retained the address and balance. The lockscreen
setting defaults to disabled; a separate actual Security toggle enabled it.
Another cold launch displayed the lock, rejected `0000` and accepted `1234`,
retaining 100 TOS. These results are recorded in
`wallet18-release-native-restore.result.json` and `wallet18-release-lock.result.json`.

A new empty native wallet was also created through the actual R8 UI. Its address
`UQBYhVBURGtQlgznUIuYwSr9NyRHjbD7jIM1HqmAKpnRd5jl` differs from the restored
fixture and holds 0 TOS. A 935 ms cold launch remained PIN-locked; after unlock,
Receive showed the same address. The root operator did not intentionally display
or archive the randomly generated recovery phrase; independent review confirms
only its absence from retained creation snapshots. The creation manifest SHA256
is `e7408b19ab998f1afac433e2ddcf5c40a33029aaf35ad6a087da8a914991c043`.

The actual QA-signed Signer 19 R8 APK imported the public native key `a71563f5709a827fad271813afc670403589781f4ac7259c7b0282b6686b2589`,
retained it after a 2430 ms cold launch and unlocked with the original Google IME
and password. An authenticated request without a callback completed to an enabled
custom QR view at bounds `[84,1007][996,1919]`. `FLAG_SECURE` protects Release
screenshots, so this result establishes the actual output view and authenticated
completion; its QR pixels were not decoded. A separate request with a local
return URL opened the browser and delivered the actual signature to
`http://10.0.2.2:18765/signer`. Independent PyNaCl verification accepted the
64-byte signature for the frozen public key and body hash
`75746f815ed146820318296d5ae0822c5fc0b82e6e1ec8b7dd621d81fd70dc0a`;
a changed signature and a changed body hash were both rejected. No transaction
was broadcast. The combined R8 Signer manifest SHA256 is
`adbc20b0e3bc443ca33f97d0355d43ab5f4e0c3d0fb7cf984773fed30532e7c5`;
the independent callback/body-mutation result is
`037bde0e3bf9443175b0928892cb9e871687d8d6b5cb45bedf41c519c47d4ca3`.

An independent filesystem-only review passed 17 checks of the creation and Signer
R8 records, artifact hashes, cold-persistence state and actual callback signature,
including both tamper controls. It did not operate a device, make a network
request or generate a new signature. Its manifest SHA256 is
`b14c3063e3989b8e19e61bef69f185982f77807557856c3d73a0ad39560b730c`.
Protected screenshots do not establish Release QR pixel readability, physical
camera scanning or complete visual layout acceptance. The independently recovered
Release signature bytes come from the actual callback.

### Preserved failures and corrections

Original failing logs, XML, screenshots and candidate manifests remain in the
artifact directories; none are rewritten as successful runs. Earlier partial
Wallet groups and old Signer 3/Release65 results are historical evidence for their
own hashes. The final counts use the complete final runs described above.

- Actual first-deployment UI exposed sender StateInit attached to the recipient
  message. Native sender initialization was corrected in the external envelope,
  with two discriminating JVM tests, an actual deployment preflight and the entire
  new Wallet 45 run. The strict recipient-deployment guard was retained.
- Signed-sequence reconciliation was corrected after a competing device could
  advance the node sequence before the transaction manager captured its baseline.
  Native receipt/retry checks now use the sequence in the signed request. All
  affected unit, Wallet device and Release checks use the repaired production code.
- Accessibility failures retained stale dialog nodes; actual XML showed the named
  input and buttons. The harness reacquires settled nodes without weakening
  assertions. QR detector failures were checked against exact encoded modules;
  PURE_BARCODE fidelity, blank-image rejection and actual production MLKit decoding
  validate the same bitmap. Legacy-prefix, normalized-recipient and mnemonic test
  fixtures were corrected against observed native values.
- Signer slide tests initially missed the actual thumb and password control.
  The test uses the observed centre, settled UI and actual internal input. A later
  clipboard check saw a prior request; a unique visible comment and exact-copy wait
  distinguish the current body. These repairs change the test APK only; the final
  unchanged production Signer APK passed all four methods.
- The no-return source path contained a null assertion after successful signing.
  The repair uses the existing QR fallback. The earlier host attempt did not
  complete authentication and is not a reproduced-crash claim. Final Debug and
  R8 authenticated completion are recorded separately with their stated limits.
- CI found five missing Russian Signer translations and the artifact gate failed
  to discover the NDK symlink. Both were fixed; no lint suppression or baseline
  update was used. An inherited download worker conflicted with V1's restricted
  permissions; retired callers and queued workers now terminate without starting
  downloads or notifications. Final full lint and permission gates passed.
- Earlier candidate 16 KB startup ANRs, SystemUI overlays and CPU-bound mnemonic
  timeouts remain in the attempt records. Quiet retries used identical APKs and
  unchanged deadlines. Final Wallet 18 actual 16 KB checks have their own complete
  results. R8 host key injection also encountered a guest CPU/IRQ-pressure ANR;
  standard IME input completed the same flow. Quick Share interception, initial
  IME/selector assumptions and the socket-restricted pre-build attempt are retained
  as failed host attempts without attributing an unproven product defect.

`bundle check`, the resolved Fastlane invocation, dependency auditing, shell
syntax, Python compilation and `git diff --check` passed during implementation.
The original 4 KB JNI wrapper failed ELF inspection; the repaired wrapper passed
host checks and actual 16 KB crypto/blur execution. These red/green records remain
separate from final APK acceptance.

### CI, remote access and validation limits

Root verified successful Android CI run
[37162130721](https://github.com/tosnetwork/android/actions/runs/37162130721)
and dependency-audit run
[37162130717](https://github.com/tosnetwork/android/actions/runs/37162130717)
on tested source/test head `3d776b55835cca8b3cd37cabd79b6e03f4067372`.
The reviewer finalizing this document checked filesystem evidence and did not
repeat network operations. A subsequent report-only commit needs its own live
workflow check after push; the source-head passes above are not labelled as that
future head's results. Draft PR3 has not been merged or published to a store.

Private read-only access to `toserver` succeeded through the existing Tailscale
SSH route to node 5's localhost RPC 8015. The current access record observed
masterchain sequence 318058. A fresh configuration read at
2026-10-04T00:04:24Z observed global ID 3, VM 18 and four validator entries with
algorithm ID 1. No deployment, funding, signing, broadcast or activation was
performed on this server. All live application payment tests used the isolated
local development chain.

The current machine's public `https://rpc.tos.network` check failed at
2026-10-03T23:46:36Z with curl error 6, `Could not resolve host`. This remains an
operational public-endpoint limit; private SSH reachability and editable-node
onboarding do not establish public default-endpoint availability. The access,
configuration and DNS records are retained in
`/Users/tomisetsu/Documents/Codex/mobile-pq-validation-20261004/remote-readonly`.
No development or private-chain ID is silently treated as a production ID.

All device evidence is from emulators, including real 16 KB guest kernels. No
physical handset, physical camera scan, store/distribution certificate, remote
production transaction or formal production acceptance is claimed. The full
45-method Wallet suite and four-method Signer suite use Debug APKs; minified
Release validation comprises the bounded actual runtime flows above. PQ validator
consensus does not make these Ed25519 personal wallets PQ signers.

The final aggregate `/tmp/android-pq-20261004/final-android-validation.result.json`
records exact evidence hashes, production pins, 59 passed provenance/scope checks,
completed totals and all limits. Its SHA256 is
`a607844a94150d72b496f6da363a6a3e28618dbb45c51001153baa7f40498f23`.
It includes separately named manifests for unit, Wallet 18, Signer 19 and Signer 21,
so repeated filenames cannot obscure artifact identity. Earlier artifact-only
manifests retain their original checkpoint statuses; this final aggregate records
the completed runtime checks.
