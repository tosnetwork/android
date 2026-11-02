# Proof-backed Quantum balances

Quantum balance refreshes use the retained wallet UUID, QSI1 birth identity and
locally provisioned network profile. The selected profile for new wallets never
replaces this binding. The reader opens the existing native rollback checkpoint;
a missing checkpoint is an error, not permission to enroll again.

The reader authenticates Config19, checks its canonical signed global ID, then
reads the account at that exact masterchain checkpoint. It checks account identity,
compiled code for active state, network and wallet ID, complete storage and shard
freshness through the existing native/account boundary. Both proof capabilities
and the final account snapshot are checked against the local clock, with a
300-second maximum age. Reading a retired, frozen or rescue-only wallet does not
require PRIMARY eligibility or CP48 admission.

Balances are recorded native amounts, not spendable amounts or fee estimates.
Unsigned120-bit nano values convert through exact BigDecimal scaling; no Long,
Double or decimal rounding is involved. Absent, prefunded, frozen and active
states remain distinct in the account observation. A frozen account is marked
initialized for display but gains no signing or spending authority.

The main wallet screen bypasses ordinary account/indexer balances and old screen
or total-balance caches for Quantum. It displays native TOS, refreshes every two
minutes and supports pull-to-refresh. A proof failure removes the old numeric
balance and shows an unavailable panel. Receive still uses the locally retained
address. A later valid read restores the display without restarting its flow.
A displayed observation is the last verified balance; it is not a continuously
live authorization to spend.

Quantum totals returned by AssetsManager are native TOS amounts regardless of a
fiat preference. Picker and balance-widget callers label that unit explicitly;
no exchange price is invented. Missing proofs yield no total and an unavailable
label, allowing other wallets/widgets to continue. Nullable token lookup callers
receive null/empty on Quantum read failures; cancellation propagates. The raw
native-balance getter throws on failure and has no zero fallback. The main-screen
loader handles failures explicitly. Quantum token deep links route to the native
wallet page before any ordinary token lookup. Generic Send/Transfer/Camera paths
remain gated until the dedicated Quantum transaction flow is available.

## Validation scope

- Native account instrumentation:33 passed, including five new reader cases.
  Removing the Config19 network comparison makes its targeted runtime assertion
  fail; restoring it passes the complete33-test suite.
- Public recorded absent/prefunded/active states exercise the enrolled reader;
  the active wallet-ID0 case also exercises the persisted creation-record entry.
  New mobile creation retains wallet ID0; fixtures with IDs1/2 do not weaken that
  enrollment constraint. Recorded proofs are checked at their recorded clock.
- Projection instrumentation: two passed, including exact maximum120-bit nanos.
- App unit tests:14 passed. Full App and test APK builds passed. Brand and V1
  static checks passed. Native proof-library packaging was independently compared
  with all four NDK-stripped pinned builds.
- A fresh three-validator network from TOS main295d5029 has passed creation,
  native absent-balance display, an ordinary false-balance positive control,
  rejection of invalid proof data, explicit UI failure and retry using the
  retained anchor. The final caller-fix run also passed picker/nullable-path proof failures and
  a nonzero funded total under USD preference, with explicit TOS unit rendering.
  The successful run retains the exact installed APKs and source hashes.
- Initial scoped review rejected picker error propagation, fabricated fiat zero
  and token-deep-link routing. Those findings were fixed; scoped re-review accepts
  the increment. Reviewers read code only and did not execute these tests.

The local runner installs APKs from Gradle output metadata, checks required DEX
classes and retains the exact installed copies. A legacy intermediates APK
mistake caused a test setup failure; it is not counted as a functional run.

The mobile native proof pin remains1aa8821c, while the current live node is built
from295d5029. Final source alignment is a separate delivery gate. Payment,
deployment, backup/rescue/migration, final full-PR review and release acceptance
remain incomplete. No signed transaction, custody of synthetic genesis funds or
production deployment is established by this read-only increment.
