# Android Quantum payment implementation checkpoint

This increment adds the dedicated App payment route, existing App passcode/biometry confirmation and production encrypted custody, exact single-transfer intent, durable single-authorization journal, persisted native estimation, full balance-difference fee quote, explicit final fee confirmation, exact-message broadcast, and authenticated sender outcome traversal. No fee vault is used.

The Quantum send availability gate remains closed in the committed source. This checkpoint is not complete App payment acceptance or release readiness.

## Verification on 2026-10-28

Android baseline: `fdcda38bfa7c508f0a926122376c040ce87429d9`.
TOS native pin: `127fd94d9274d663b6ff96748ad8e571b9b797e8`.

- arm64-v8a native proof library and client-owned JNI adapter: actual build exit 0. The other three ABIs were not rebuilt in this checkpoint.
- `QuantumPaymentIntentTest`: 2 tests executed, 0 failures/errors/skips. Covers canonical exact decimal amounts beyond Double precision, 120-bit boundary, confirmed-field action hashes, and UTF-8 limits.
- Production account Kotlin, actual App Kotlin, security Android-test Kotlin, account Android-test Kotlin: compiled successfully, final batch exit 0.
- New persisted JNI simulation and durable outbox Android tests: compiled, not executed yet.
- Actual App passcode/Keystore/first deployment/payment/receipt acceptance: not executed yet.

The first Gradle attempt was interrupted (exit 130) during configuration-cache graph serialization, before product compilation. The same checks then ran with `--no-configuration-cache`; compilation found and fixed an unavailable Android `Os.unlink` call and a view receiver shadowing error. The final identical check batch passed. Neither the interrupted attempt nor the compile-only device tests count as runtime success.

Raw local evidence directory: `/Users/tomisetsu/validation/mobile-quantum-handoff/`:

- `android-payment-arm64-native.log` and `android-payment-arm64-native-manifest.json`: native inputs, pinned head and artifact hashes.
- `android-payment-kotlin-compile.sh`, `android-payment-kotlin-first.log`, `android-payment-gradle-first-threads.txt`: first attempt.
- `android-payment-kotlin-no-cc.sh`, `android-payment-kotlin-no-cc.log`, `android-payment-kotlin-fixed1.log`, `android-payment-kotlin-fixed2.log`: identical checks and compiler fixes.
- `android-payment-kotlin-fixed2-source-manifest.json`: compiled WIP file hashes, before this documentation-only addition.
- `android-payment-intent-unit-tests.xml`: actual unit-test report.

## Remaining acceptance and recovery work

A restart can re-estimate only a journal in `SIGNED`, before any broadcast transition. Fresh proofs from the original profile must reconstruct the original request digest, with the original deadline and deployment flag; the exact saved BOC is reused without another signature. Stored fee approval metadata is never restored as a native quote capability.

`SUBMITTING` / `UNKNOWN` restart recovery currently permits outcome verification only. Signing interrupted without a durable BOC, uncertain writes, archival and slot release remain blocked and need the remaining R03 implementation. A current nonce match alone cannot authorize recovery or release after possible account deletion/redeployment. A result receipt is not slot-release authority, and RPC acceptance is never labelled paid. The reader proves the exact sender transfer; recipient credit requires a separate observation.

The receive QR route now requires a reminder before showing a Quantum funding address: retain the original secret recovery material; public QSI1 data alone cannot restore signing access. This reminder is not evidence of a completed secret backup or R05 recovery acceptance.
