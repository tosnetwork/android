# R03 A: authenticated expiry capability

This increment strictly decodes the original stored QSP1 request and issues a private, non-serializable expiry capability from a fresh authenticated account observation under the original trust profile. The account's proven shard time must be strictly greater than the original request deadline. Current nonce/epoch/authority changes, local time and history-page booleans do not establish expiry.

The normal outbox mutex/flock re-reads and validates the complete current authorization before capability publication and consumption checks. RESERVED, SIGNING, SIGNED, SUBMITTING and UNKNOWN are supported. Missing signature/BOC is explicitly bound; an interrupted SIGNING entry is never treated as proof that signing did not occur. Unsigned cancellation remains a separate path.

Binding uses stable semantic fields: birth identity cell hash, the three profile fields, operation, role, authority, original counters/deadline, phase, deployment, exact intent, public key, exact original request/signature/message bytes with explicit absence, and typed fee-approval fields. It does not hash reserialized identity BOCs, anchor JSON or whole entry JSON. A legally reencoded QSI identity with the same cell hash remains the same identity; different raw request or external-message bytes are rejected even when their cell roots are identical. Receipts now have private construction behind the authenticated transaction walk and retain the same complete binding.

This A-stage API only authenticates and validates capabilities. It never retires/deletes a slot, writes a public summary, signs, broadcasts or retries UNKNOWN. The production availability gate remains closed. B/C retirement transactions and uncertain-write recovery, later real-chain expiry/release/second-payment acceptance and the complete R03 work remain unfinished.

## Validation

Based on `2e916026d8b94f6f7e5c3ea8d68c9686a0c7b42a`; native pin remains `127fd94d9274d663b6ff96748ad8e571b9b797e8`. No native source or TOS changes and no new test chain.

- 12 JVM tests actually executed: 4 strict-parser tests, 2 exact intent tests and 6 existing self-pay tests; no failures/errors/skips.
- Account test APK, main App APK and main UI-test Kotlin compiled successfully. The restored build also passed; unchanged JVM checks were up-to-date on that restoration build.
- Initial valid device baseline: 8 expiry tests plus the existing outbox test passed (9/9). Coverage includes all five phases with deadlines before/equal/after proven shard time, stale capabilities, future local clock, wrong identity/profile/operation/phase/deployment, missing-to-present signing state, equivalent-root different raw bytes, 32 redecodes/key reorders/equivalent identity encodings, alias workers and uncertain pending material.
- Three independently rebuilt single-point controls each fail exactly their designated assertion: changing `>` to `>=`; omitting expiry-consumption freshness; omitting exact signed-BOC byte comparison. Compilation errors or arbitrary exceptions do not count as valid controls.
- Original source bytes were restored and all nine frozen source hashes matched; restored device suite passed 9/9 (5.683 seconds) on arm64/API36.1/16K. The owned emulator was then stopped.

The first device attempt rejected two test methods during JUnit initialization because their expression bodies inferred a Throwable return type. Explicit Unit return types fixed the test driver without changing assertions. That failed attempt did not execute the eight expiry tests and is retained separately.

Raw evidence: `/Users/tomisetsu/validation/mobile-quantum-handoff/android-R03-A-final/index.json`, `android-R03-A-controls/`, `android-R03-A-jvm-initial/`, and the named compile/device logs. Final A APKs are frozen beside that index. The earlier 2e91602 APKs are separately hash-verified and frozen in `android-payment-2e91602-frozen-apks`; previous payment/credit evidence is unchanged. A real nonempty receipt/payment UI run was not repeated in this no-chain A batch; that runtime evidence remains scoped to its earlier exact source checkpoint.
