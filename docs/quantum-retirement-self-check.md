# R03-B/C authorization retirement — implementation work in progress

The frozen third core build passed, and its API36 arm64 device batch passed 3 native-directory and
20 account methods (11 retirement, 8 expiry, 1 existing outbox). Later C work added bounded `.pending`
inspection/reconciliation and two guard-removal controls; the restored API36 account batch passed 10/10
with no skips and the strict host checker passed 8/8. Evidence is indexed at
`/Users/tomisetsu/validation/mobile-quantum-handoff/android-R03-C-guard-controls-1793243414000000000/execution-index.json`.

The real two-payment App UI path also passed on an earlier unpublished availability-only candidate, followed
by an independent same-anchor sender/recipient audit. That run is not the current exact-source acceptance:
its recorded outbox and UI-test hashes are `c5291d49…` and `020cf46b…`, while the current tree hashes are
`296aca3c…` and `5e7106ef…`. The later single-payment `EXPIRED_UNVERIFIED` App path is a separate successful
fresh-chain result with no recipient-credit claim. R03 remains incomplete until the relevant App flows are
re-run against the final exact source and current feature head, then reviewed and checked by CI. The production
availability gate remains closed; UNKNOWN retry remains disabled.

## Invariants and storage budget

- Fresh authenticated account **shard** time must be strictly after the original request deadline.
  Quote expiry and the local clock never release a slot. Full A semantic/exact request/message binding
  is rechecked under the original global mutex and file lock, including immediately before deletion.
- Exactly one global retirement workspace blocks ordinary reserve/sign/submit while unresolved.
  Full entry snapshot is at most 2 MiB. It is copied from the same no-follow descriptor whose raw
  canonical bytes were fully validated; no JSON/identity reserialization is called the original file hash.
- At most 64 public summaries, each at most 64 KiB (4 MiB total), plus one bounded summary temporary.
  Workspace entry + decision stays below 2 MiB + 31 KiB; with summary temporary and publication overhead
  the transaction stays below the planned 2 MiB + 128 KiB + 16 KiB workspace/publication budget.
- Root enumeration stops above 256 names; history above 65; workspace above 3 and rejects unknown content.
  Native local I/O supports API 24+, with no TOS/VM changes. It uses no-follow directory open,
  fdopendir/readdir, NAME_MAX and RAII descriptor closure; illegal basenames and overflow fail closed.
- When history is full, only the oldest durable completed record unreferenced by retirement and not
  matching an active operation can be removed, ordered by recorded completion time then UUID.
  No pending/active authorization is removed to make space; lack of an eligible record is an error.

## Commit and recovery order

Under the same mutex/flock: validate capability/optional private receipt; preserve complete entry and
decision, sync files/workspace/parent; publish release_pending summary and sync history; recheck original
capability and exact current operation; unlink canonical and sync outbox even after a previous ENOENT;
publish release_completed and sync history; finally clean workspace files, directory and parent.
Every continuation also re-syncs both retained files and the workspace directory before publishing or
deleting canonical. A parseable entry/decision left visible after a write interruption is not assumed durable.

Receipt classification stays separate from expiration. Results are sender-sent, included without exact
transfer (with the mismatch reason), or expired with payment outcome unverified. No result asserts
recipient credit or that funds did not move. Public JSON is local display data and cannot mint capabilities.

Dedicated recovery readers validate the full retained entry and original profile and obtain fresh native
proofs even after canonical unlink. Completed cleanup reconfirms history directory durability first;
it can continue after entry/decision deletion was interrupted and never touches any canonical authorization.

One narrow unpublished-stage reconstruction is allowed: protected, schema-bounded stage with exact same
operation as a fully valid canonical, no decision (including ambiguous temporary decision), no summary
for that operation and no global signing pending. A newly minted fresh capability must bind the full
canonical again at consumption before copying its verified raw bytes into stage metadata. The canonical
is unchanged. Missing/different canonical, a published/ambiguous decision, unexpected files or unknown
signing pending retain the block. This is not arbitrary corrupt-journal or C recovery.

## Frozen core validation and remaining work

- Eleven retirement device methods passed, including all five phases, 47 named injected durable I/O/restart windows,
  canonical-missing recovery, stale capability rejection, old-operation/new-slot separation, partial
  completed cleanup, narrow stage reconstruction, ambiguous-decision/pending refusal, 65-operation
  history rollover, and concurrent retirement followed by an explicit new reservation.
- A dedicated decision-file-sync interruption passed and verifies that recovery re-syncs both files and their
  workspace and that an injected workspace-sync error retains the canonical and creates no public summary.
- Three native directory device methods passed, covering exact 256/257 bounds, illegal basenames, symlink/permission
  refusal and repeated failure descriptor cleanup. This is API36 arm64 runtime evidence; API24 actual-device
  runtime and the other three ABI integrations remain unrun. The arm64 bridge was built with minSdk24 intact.
- Each exact expected method had one start/finish and a unique matching current ID; no skips or assumptions.
  A read-only audit rejected 16 designated bad-log controls. Raw logs, frozen APKs and all source hashes live
  under `validation/mobile-quantum-handoff/android-R03-B-third-compile/` on the validation host.
- Designated deadline/freshness/binding guards remain A evidence. B summary-before-unlink, mandatory directory
  sync, pending block, stage fresh-cap and same-operation controls passed their specified red/green controls;
  R03-C same-operation and phase-rank removals also hit their expected conflict assertion, with the original
  source restored before the final 10/10 device run. Evidence is in the index above. These controls do not
  simulate kernel power loss or prove API24, other ABI or hardware behavior.
- The explicit App end/history UI and two-payment test have run successfully on the earlier source snapshot:
  `freshNetworkEndsOriginalAuthorizationAndExplicitlyPaysAgain` completed once with protected custody and a
  durable first retirement; independent raw-BOC/account-proof audit confirmed both sender transactions and
  recipient inbound transfers. The later expired-unverified App test also passed once, but deliberately makes
  no recipient-credit claim. Since the two-payment run, outbox and UI-test sources changed; re-run the full UI
  path and capture exact current source/APK hashes before using it as final-head evidence. No production
  availability gate opens.
- A current uncommitted-tree static review found no confirmed P0/P1/P2 code defect. This does not replace exact
  source runtime/UI tests, API24/other-ABI/device coverage, accurate-head CI, or merge review.
