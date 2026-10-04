# Android TOS modernization and validation, 2026-10-04

Historical modernization baseline at `dd3edce`, before user-wallet PQ signing.
The following tests/claims describe that baseline; current PQ signing is documented
in [the follow-up implementation and validation report](mobile-pq-signing-20261004.md).

| Current gate, source/test `75d3473d19045185cde3a4032ac1dc365670785c` | Result | Evidence |
| --- | --- | --- |
| Android CI and dependency audit | PASS | Runs [37184581287](https://github.com/tosnetwork/android/actions/runs/37184581287) and [37184581240](https://github.com/tosnetwork/android/actions/runs/37184581240) |
| Local `make test_ci`, four Debug/test APKs, both R8 Releases and artifact gates | PASS, whole exit 0 | `android/native75-local-ci/acceptance.result.json` |
| Accepted JVM inventory | 99 unique methods / 29 suites; 0 failure, error or skip | Archived XML plus configured real-node API rerun |
| Full Wallet device suite | PASS, 43 UI + 3 native scenarios, one uniform APK pair, whole exit 0 | `android/native75-full46.result.json` |
| Wallet layout matrix | PASS, four size/font/theme/locale configurations | `android/native75-matrix4-completed/acceptance.result.json` |
| Actual 16 KB kernels | PASS, API 36 native 3 + API 37 native 3/UI 4 | `android/native75-real16k` |
| Wallet R8 runtime | PASS, native restore/lock/cold persistence, actual 0.01 TOS delivery, new-wallet creation | `android/native75-r8-restore-payment-completed`, `android/native75-r8-create-completed` |
| Signer Debug four-method suite and bounded R8 signing runtime | PASS evidence reused for byte-identical current APKs; no new execution at this head | `android/legacy-signer-ui4-final` and original Signer 19 R8 records |
| Full Release lint | 0 Error/Fatal; Wallet 5 warnings, Signer 105 warnings | XML beneath `android/native75-local-ci/lint` |
| Private deployed-node access | Seven RPCs answered at height 402716; read-only | `remote-readonly/native75-local-complete-seven-rpc.result.json` |
| Public default RPC | Final check failed DNS resolution; public availability remains blocked | `remote-readonly/final-public-rpc-current.result.json` |

All requested current local Android gates are complete. This report binds them to
commit `75d3473d19045185cde3a4032ac1dc365670785c`, starting from Android
`8035a30caa1b3b9628c675d32fe2868711ee61cd` and targeting the protocol at TOS
`ee5ad71c343eb2227a8bd42d57bea259da4da0ec`. The worktree is clean. The source-head
cloud workflows above passed; a subsequent documentation commit requires its own
workflow check. Draft PR3 remains unmerged and nothing is published to a store.
Evidence paths are relative to `/Users/tomisetsu/Documents/Codex/mobile-pq-validation-20261004`.

## Resulting behavior and protocol scope

New wallets use native TOS mnemonic salts and frozen V5R1 code hash
`086a86aa9913c0ec52277adbb7e4b5695964dbb8c817ad0c305cdd345bbfac69`.
The signing cell covers opcode, signed int32 global ID, plain uint32 subwallet
number, expiry, sequence and actions, with Ed25519 signature at its end. Native
version 6 is distinct from legacy V5 version 5. Existing legacy keys, code,
addresses and packed wallet IDs remain stable. Sender StateInit belongs to the
sequence-0 external envelope; ordinary native/legacy outgoing messages retain
only explicitly requested recipient StateInit.

Network identity comes from Config 19 and VM capabilities from Config 8 at one
masterchain height. Native accounts persist the expected global ID. One captured
node/credential remains bound to sequence, fee, balance, broadcast and retries,
even if settings change during confirmation. Missing or malformed active sequence
values never become deployment sequence 0. Fees require all four nonnegative
integral fields and reject missing values/overflow. Onboarding exposes RPC editing
and retry before a wallet exists, permitting recovery from an unavailable default.

Lost broadcast responses are reconciled against the exact submitted external BOC
hash, including StateInit, successful compute/action execution, expected real
outputs and no skipped actions. A different device consuming a sequence cannot
confirm this payment. The sequence actually signed anchors checks before the
initial broadcast and retry delays. An advanced sequence without this message's
receipt remains ambiguous and stops automatic replay. Normalized hashes that drop
deployment state are not compared to the node's original incoming message hash.

TOS and legacy TON recovery domains remain separate. Real dual-valid phrases
require explicit TOS or Legacy selection; ambiguous automatic import and mixed
repository profiles are rejected. Vault metadata, derived keys and cached public
keys must agree. Real SQLite/Room migrations from versions 1/2/3/4 to 5 preserve
legacy wallet/vault keys, addresses and version while adding nullable network
identity. Native version 6 requires that identity.

Native Signer keys require the strict native request profile before confirmation,
authentication and signing, independently of attacker-supplied or omitted URI
version labels. The supported offline profile is ordinary internal TOS, mode 3,
empty bodies or complete UTF-8 comments. Unknown/truncated payloads, extra
currencies, recipient initialization, external messages, sweep/carry semantics,
hidden headers and trailing data are rejected. Confirmation shows nine decimal
places and numeric network ID. Native export stays local and does not open TON
emulation. Authenticated no-callback requests use local signature QR output;
legacy requests/CSV vaults retain their own key profile.

PQ validator consensus permits Ed25519 personal wallets; these Apps explicitly
retain Ed25519 authentication and do not become ML-DSA/Falcon wallet signers.
Capability discovery reports ML-DSA from VM 16 and experimental Falcon from VM 19.
Ordinary native V5 requires VM 6, verified across VM 0–18: 0–3 exit 6, 4–5 exit 5,
6–18 successful compute/action with real output. VM 18 therefore supports the
ordinary flow and does not advertise Falcon. PQ relayer UI, key migration,
gasless services and chain activation are not implemented.

Independent TOS SDK/Python vectors agree on code/address/signing hashes and native,
legacy and dual-valid mnemonic keys. Real VM controls cover old ABI, wrong network,
expiry, sequence/replay and bad Ed25519 signature. Frozen blind-signing negatives
include truncated jetton, hidden StateInit, external output and extra currencies.
The first three emitted real outputs in the original VM proof; extra-currency
sending was skipped because the sender lacked that currency. Its evidence is a
hidden-semantics negative, not a claim of extra-currency theft.

## Ordinary legacy and native MAX repairs

All five shipped ordinary legacy revisions transferred on the isolated node while
its wallet RPC still returned active `wallet=false`/null sequence. Typed legacy
reads now validate one captured address snapshot against the exact V3R1/V3R2,
V4R1/V4R2 or V5R1 code, revision layout, public key, wallet ID and V5 signature
flag. Reconstructed original sequence-0 StateInit must reproduce the address;
typed callers also bind their contract metadata. Code/data BOCs require one
ordinary level-0 root, including rejection of nested pruned data. Frozen, unknown,
malformed, native and V5 Beta accounts receive no fallback. Valid current plugin
or extension dictionaries are retained; reconstruction uses the original empty
state. Fees share this validated snapshot and add initialization only for an
explicitly uninitialized account. Legacy signature layouts provide the actual
signed sequence for reconciliation. Canonical V5 hash remains
`20834b7b72b112147e1b2fb457b84e74d1a30f04f737d4f62a668e9552d2b72f`.

The current full suite restores the public legacy `8915…675c` account from 25 TOS
and no code/data, performs first App deployment and the already-active second
send, and reaches sequence 2. No external bootstrap masks deployment. Each gross
1,000,000 nanoTOS output matches the original recipient input cell; net credit is
exact gross minus BOC-verified recipient fees. A code-free recipient may skip
compute while crediting funds. Original sender StateInit is present externally
at sequence 0 and absent from recipient messages. The native first deployment
similarly verifies exact code/key/global ID/subwallet and 0.01 TOS delivery with
Unicode comment `TOS V1 测试 🌌`. Independent read-only observation is recorded
in `android/native75-app-deployment-observer.result.json`; it does not imply
proof-authenticated production finality.

The earlier MAX reproduction showed balance 101.98454647 versus total
101.984773682 TOS: an erroneous additional 227,212 nanoTOS fee. UI formatting changed
decimal scale and data-class equality dropped MAX despite equal numeric amounts.
The repair uses numeric `Coins.compareTo == 0` only for native MAX. Its send mode
remains 130 (carry-all 128 + ignore-errors 2); ordinary/token MAX remains 3. Two JVM
methods serialize the actual scale-different V5 action and reject one-nano-less
or non-native carry-all. Native fee display skips unused fiat conversion;
cancellation is rethrown, while real fee errors and ordinary balance-plus-fee
checks remain. Observed rate 404s were not established as the cause. The current
full device suite passed actual native MAX. Separate R8 smoke covers MAX preview
and process-abort with unchanged chain, not toolbar cancellation or R8 carry-all
broadcast.

## Toolchain and security dependencies

Wallet is 1.1.0/code 3/minimum API 24; Signer is 0.3.0/code 24/minimum API 26.
Both compile/target API 36. Tested tools are JDK 17.0.20, Gradle 8.14.3, AGP 8.12.3,
Kotlin 2.2.20, NDK 27.0.12077973 and current full R8 8.12.22/resource shrinking.
API 36 meets the [Google Play target API requirement](https://developer.android.com/google/play/requirements/target-sdk)
starting 2026-08-31. Fastlane 2.240.1/rubyzip 3.7.0 satisfy rubyzip >=3.4.0 for
CVE-2026-85396 / GHSA-47m2-wp7j-p9vc; automation/CI require Ruby >=3.3 and local
bundle validation used 3.4.6. CI compiles both instrumentation APKs and runs
Release lint/artifact gates. Russian translations and NDK symlink discovery were
fixed without lint suppression or baseline changes.

The Sodium wrapper links static Sodium; it and blur use both NDK 27 16 KB linker
flags. CameraX is compatible stable 1.6.2. All packaged arm64/x86_64 native LOADs
and ZIP alignment are checked, with rounded RELRO/writable overlap checks that
avoid false positives from official padding. Both current App artifacts contain
12 stored 64-bit libraries with valid CRC/16 KB alignment. Actual ARM64 API 36/37
kernels report PAGE_SIZE 16384. API 36 linker compatibility is false, API 37 fatal,
and both disable package compatibility. All six ARM64 libraries load; Sodium
roundtrip/wrong-key/tamper checks and JNI blur pixel assertions pass. Four API 37
UI repeats cover node replacement, old DB and explicit TOS/legacy recovery.
[Android's page-size guidance](https://developer.android.com/guide/practices/page-sizes)
provides the packaging requirements; loading camera/path libraries is not full
physical camera/graphics-path behavioral acceptance.

## Counts, immutable artifacts and bounded Release runtime

The local whole Make wrapper exited 0. Its initial mapper skip from omitted
`TOS_TEST_ADDRESS` remains archived. The entire API task then reexecuted against
explicit loopback RPC/public active `8826…0cfe`, exit 0, replacing its prior 43
method results. Accepted XML has 99 unique methods/29 suites/zero skips; unchanged
UP-TO-DATE modules keep original timestamps. Reruns and configurations are not
added to unique method counts. Wallet46 has 20 standalone + 23 persistent UI +
three native methods on one immutable pair; its separately declared matrix method
is excluded. The terminal PASS and actual whole exit 0 are both required.

The immutable Debug/unsigned files are beneath `android/native75-local-ci/apks`.
Original unsigned Release files are untouched; the QA copy is separately signed.

| Artifact | SHA256 |
| --- | --- |
| Wallet Debug | `3412285caed71095fdab3d91b471730ffcf029675fdca3a4f4e0665c7b862c5e` |
| Wallet instrumentation | `9eb576558134d569a298f6188d15bfd97f0b987940e8fdf7622aa4fea9049d95` |
| Signer Debug | `5461ad6b6068239515340ba633a7d8dfd31f23907a17962d8de086ad9863814a` |
| Signer instrumentation | `bb9668913fde277595bada33982d045266633a8f326a8a4e3dbaa84572cacf77` |
| Wallet unsigned R8 | `130c0edbe3c3cf003a1a154859a7663d4915a6d941b59abe3a7c35aa5684b976` |
| Signer unsigned R8 | `eb848613247b9b2b99a00298ed81ca4abb2483f2e1564d66ca5b0c8adaf0e552` |
| Wallet local-QA-signed R8 | `6cd9d479da3710f7c8bd70280ccf0d4e4155b50497608c0a15bd00e7e41d07c3` |

Current Wallet R8 restores public native `6d…3555a`, exact address/100 TOS, cold
persistence, wrong PIN rejection/right PIN unlock. Actual first-deployment payment
reaches sequence 1 with successful compute/action/one output/no skips; original
message hash matches the recipient, gross/net 10,000,000 nanoTOS and fee 0. Native
creation produces `UQDDkGBeBwBbL_NjA5P6JW3nt_EB8LEPNrwL72Tfoq-vO-Us`, 0 TOS,
with cold persistence; generated phrase is never opened/printed/archived. All
three runtime wrappers exited 0. Host elapsed timings are not startup benchmarks.

Signer main tree `0c66db608b69c5e8244c09694fc28871cc082f13` and build-script blob
`3563c234fe6ddf22abf539dc100aca4a9c71c91a` are unchanged. Current Debug/test bytes
match the four-method accepted run at 79d427; unsigned R8 matches the previously
executed QA-signed Signer 19 program. This reuses exact bytes, not a new execution.
That R8 flow imports native key, retains it after cold password unlock and
completes authenticated no-callback QR view. FLAG_SECURE prevents Release QR
pixel decoding. A separate browser callback returns a real 64-byte Ed25519
signature for body hash `75746f815ed146820318296d5ae0822c5fc0b82e6e1ec8b7dd621d81fd70dc0a`;
independent PyNaCl accepts it and rejects changed signature/body. No broadcast.

## Preserved failures and acceptance limits

Historical logs/XML/screenshots retain their original hashes and status. Earlier
Wallet45/matrix/16 KB/Release passes do not clear later changed Wallet binaries.
The current complete runs supply the totals above; unchanged Signer reuse remains
explicitly bounded. Retained failure directories include:

- `android/legacy-jvm-initial-fixture-failure` and the referenced-StateInit test
  decode failure: explicit live fixture and original stored-cell decode corrected;
  exact serialized/code/data equality remains, no product codec-loss claim.
- `android/legacy-wallet-ui46-recipient-storage-fee-failure`: two App legacy sends
  succeeded, then an incorrect net assertion failed. Gross 2,000,000 minus verified
  fees 0+1 gives 1,999,999; exact fee-aware test repair retains all message/init
  guards. Its initial explicit-codec compile failure is separately retained.
- `android/legacy383-full46-maxsend-initial-failure`: whole exit 1 after 37 passes;
  manual no-send reproduction confirms the scale-sensitive MAX defect corrected
  at the current head. Rate 404s remain non-causal observations.
- `android/native75-initial-local-node-skip`: omitted input checkpoint; replaced
  API XML is not double-counted. CI Ruby/translation/readelf failures are retained.
- Five `android/native75-r8-…-failure` driver attempts: wrong Import-title selector,
  unquoted public phrase, helper IME injecting phrase into recipient, duplicate
  keyboard Back closing Send, and background controls beneath Scan QR overlay.
  XML and unchanged APK distinguish host failures; separate wrong-AVD/unsigned-
  helper-install attempts are setup failures. Completed Release runs retain them.
- Earlier Signer thumb/password/clipboard failures and one empty MLKit QR decode
  remain. The latter cause is unconfirmed; unchanged whole-four quiet retry passed.
  Earlier startup/SystemUI ANRs and mnemonic timeouts remain separate attempts.
  A first completed-run recorder invocation rejected a relative historical path;
  correction used the same log and exit status without changing parser guards.

Fresh read-only SSH/Tailscale observed all seven RPCs at height 402716 at
2026-10-04T08:58:25Z. Independent config BOC decoding records global 3, VM 18,
capabilities 494 and four algorithm-1 validator entries. No server write, transfer
or activation occurred. All payment tests use isolated development chains/public
fixtures. The final public RPC check at 08:58:49Z failed curl 6/DNS; A/AAAA queries
to both 1.1.1.1 and 8.8.8.8 returned NOERROR with no address records.
Configurable onboarding/private reachability do not prove public availability,
and development ID 3 is not a production default. Task-owned emulators/controllers
were stopped and five ephemeral QA signing files removed; cleanup evidence is
`task-owned-cleanup.result.json`.

Reusable gates are `make test_ci` and `make test_v1_acceptance`; Gradle uses two
workers, disabled parallelism and 2 GB heap. Real-node tests need explicit RPC and
address inputs; frozen APK reuse needs both matching SHA256 values. Fresh deployment
cases need fresh fixture state. Public phrases must never hold real assets.

All device evidence is emulator-based, including real 16 KB guest kernels. Debug
suites, bounded minified Release runtime, parser QA and static checks have separate
scopes. No physical handset/camera scan, store/distribution certificate, store
publication, installed-version upgrade, production transaction or formal
production acceptance is claimed. QA private keys are not archived. Protected
Release QR pixels remain unverified. A report-only commit's CI is pending until
that commit is pushed and its actual workflows complete.
