# Android Quantum payment checkpoint

The dedicated App route now uses the existing passcode/biometry confirmation and production encrypted Android Keystore custody for an exact single-transfer intent, durable authorization journal, persisted native estimation, full balance-difference fee quote, explicit final fee confirmation, exact-message broadcast, and authenticated sender outcome traversal. No fee vault is used.

The committed Quantum send availability gate remains closed. The runtime evidence below used an unpublished availability-only source variant on a disposable emulator and isolated chain. The original gate was restored and the final closed candidate rebuilt and installed before the emulator was stopped. This is not complete recovery, physical-device, all-ABI or release acceptance.

## Verification on 2026-10-28

Baseline before this payment implementation: `fdcda38bfa7c508f0a926122376c040ce87429d9`.
Initial independently reviewed implementation: `35f226ba1e0361fed83934e06a51ad651f2157e3`.
Native TOS pin: `127fd94d9274d663b6ff96748ad8e571b9b797e8`.

- arm64-v8a native proof library and client JNI: actual build exit 0. The other three ABIs have not been rebuilt against this pin locally.
- Exact amount/intent unit tests: 2 executed, no failures/errors/skips; canonical decimals beyond Double precision, the 120-bit boundary, confirmed-field hashes and UTF-8 limits.
- Final gate-closed main App, main UI-test, security-test and account-test APK batch: actual exit 0; production and test Kotlin compiled.
- Final native/device suite: 8/8 passed, including persisted simulation exact-byte binding, refusal to initialize missing state, transport exception preservation and lost-checkpoint refusal.
- Final outbox device test: 1/1 passed, including durable SIGNING before custody access, retained failure state, exact amount type, two-thread single-slot reservation and refusal to delete uncertain pending writes.
- Instrumentation result-gate controls: 7 passed, including refusal to treat the previous seven-test suite as the complete new eight-test selection. `v1-static` passed.
- Actual App UI: 1 passed in 61.583 seconds on arm64/API36.1/16K, using actual onboarding/PIN, production Keystore custody, pre-funding reminder, public-source funding of the fresh App-generated address, first deployment plus a 1 TOS payment, full native quote, fee confirmation and authenticated sender outcome. The test never injects a fixture signer into the App.
- Independent host audit: fresh proofs from the same anchor and native hash-bound raw transactions link the exact App external message to the sender transaction and its unique output to the recipient transaction. The recipient transaction successfully credits the 1 TOS message; this is separate from App receive-UI acceptance.

The journal remains UNKNOWN after the observed receipt. Neither RPC acceptance nor receipt observation releases the authorization slot.

## Evidence and failed attempts

Raw local evidence is under `/Users/tomisetsu/validation/mobile-quantum-handoff/`; the small checked-in `quantum-payment-app-evidence.json` indexes it.

`android-payment-final-closed-manifest.json` records final source and APK identities. The successful unpublished App run is `android-app-payment-acceptance-1793194619255987000`, including `results.json`, source/APK hashes, public receipt/outbox, exact BOC, bounded public RPC material and `independent-credit/index.json`. The availability-only patch and exact restore hashes are in `android-payment-availability-35f-20261028-01/index.json`.

Failed/interrupted attempts are retained separately:

- Gradle exit 130 during configuration-cache graph serialization, before product compilation; the same checks subsequently ran without configuration caching.
- Compiler failures for an unavailable Android API, a view receiver shadowing error, and an incorrectly placed test assertion; all were fixed before the successful final builds.
- First App run: creation stopped because the host relay was still blocked in reverse DNS during server setup. The relay now binds explicit loopback without DNS and proves readiness before launching the App.
- Second App run: creation, production custody and authenticated funding succeeded, but an unnecessary test Back action closed the payment screen. The test now clicks visible buttons directly and scrolls only when necessary, with bounded searches.

These attempts are not counted as successful payment tests. The availability-only candidate and final gate-closed APKs are indexed separately; old-head CI is not final-head evidence.

## Remaining recovery and delivery

A restart can re-estimate only SIGNED, before any broadcast transition. Fresh proofs from the original profile must reconstruct the original request digest, deadline and deployment flag; the exact saved BOC is reused without another signature. Stored fee metadata never becomes a native quote capability.

SUBMITTING/UNKNOWN restart recovery currently permits outcome verification only. Interrupted signing without a durable BOC, uncertain-write recovery, archival and slot release still require the remaining R03 work. A current nonce match does not establish safety after deletion/redeployment; release needs its own fresh proven-time protection beyond the original signature deadline. Complete secret backup, independent rescue/migration, legacy fee-state removal, remaining ABIs and final release freeze also remain unfinished. The funding reminder is not completed secret-backup acceptance; public QSI1 data cannot restore signing access by itself.
