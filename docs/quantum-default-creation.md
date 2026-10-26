# Default Quantum creation

The New Wallet entry creates a self-pay Quantum identity. Classical import remains available for existing wallets. Creation generates 24 native TOS mnemonic words without constructing an Ed25519 signing key.

## Local network provisioning

Before creation, open **RPC Node → Trusted Quantum network** on the start screen or in wallet settings. Enter the signed global network ID and the zerostate root/file hashes obtained through an independently trusted channel. Configure the explicit RPC endpoint separately. There is no hardcoded release network, automatic RPC trust or namespace fallback.

The native proof session authenticates CP8, CP19 and CP48 from that anchor. Creation checks VM version, the selected global ID, the authenticated AUTH namespace, PRIMARY retirement policy and proof freshness. The profile captured for each wallet is independent of later changes to the selected creation profile.

## Persistence and failure behavior

Creation saves the public QSI1 birth identity, anchor and account/generation coordinates before writing secrets. Public records use private, exclusive files in `noBackupFilesDir/quantum-creation`; existing records cannot be overwritten. Both account and generation are zero for new wallets.

The mnemonic is encrypted under an unlocked-device Keystore key and indexed by wallet UUID. PRIMARY and RESCUE are derived separately, wrapped with the dedicated Quantum device key, and bound to the wallet UUID, network and derivation coordinates. Both roles remain on the same device; this is not independent rescue custody or hardware PQ signing. No fee key or fee vault is used.

Only after both role writes and a final freshness check does creation insert the main wallet record. SQLite insertion errors propagate and roll back the complete batch. PIN read failures cannot authorize a replacement PIN. Failed attempts retain enrollment material instead of deleting uncertain writes. These records are not a completed backup.

New Wallet offers **Resume unfinished wallet** when an enrolled mnemonic remains without a completion marker. Resume authenticates the existing PIN, retains the original UUID and anchor, reads through the existing proof checkpoint, and compares the rederived birth identity before writing custody. It fills missing roles and verifies existing roles without replacing their ciphertext. A committed main row followed by a failed RN mirror write is completed idempotently with the same wallet. Changed creation-network settings do not change this enrollment. Only the authenticated completion path writes the completion marker after the mirror and selected-wallet ID persist; ordinary background selection does not open secret custody.

Recovery requires the original public record, proof checkpoint and protected mnemonic. A process crash before the mnemonic was persisted cannot reconstruct that attempt from the public record alone. Corrupt or conflicting custody and missing checkpoints are refused, not silently replaced or re-enrolled. This recovery is for interrupted initial creation, not backup restore or authority rotation.

The RN `walletsStore` now propagates SQLite and parsing failures, reloads committed state and serializes modifications across repository instances in one process. Repeated registration checks the same immutable identity and reconciles mutable labels. Corrupt registries are preserved for diagnosis. Other inherited RN key/value paths retain their existing behavior; this is not a transaction across all application stores or across processes.

Wallet removal requires authentication for Quantum, checks secret deletion before deleting the main row, and reports errors before dismissing settings. Full sign-out clears the dedicated self-pay custody records, including incomplete attempts. Public birth records and proof checkpoints remain; no secret is stored in those records.

## Validation

`QuantumWalletCreationTest` in the account module exercises native verification of recorded real-chain TL replies, native mnemonic derivation, protected device storage, public-record reloading, injected storage failures, wrong stored keys and expiration before publication. `QuantumWalletIdentityTest` also tests transaction rollback and exception propagation. `QuantumMirrorPersistenceTest` checks real SQLite write/read failures, malformed registries, idempotence, identity collisions and concurrent instance updates. Creation tests verify partial-custody reconstruction and reject mismatched enrollment before any secret write. These recorded proofs use their capture time and do not attest to a currently live network.

`QuantumCreationUiTest` in the main app tests profile validation, partial PIN records, and actual onboarding against a fresh running chain. Run UI methods individually on a disposable installation. Clear it before each independent case, but preserve its data and force-stop the app between `mirrorFailureLeavesAnEnrolledWalletForColdResume` and `coldResumeUsesOriginalAnchorAndDoesNotDuplicateWalletOrReplaceKeys`. Those two methods inject an actual SQLite mirror-write failure, then resume in a different process after changing the selected creation network; they assert the same UUID, birth identity and role ciphertext hashes, with no duplicate row. The live method requires instrumentation arguments `quantumRpc`, `quantumGlobalId`, `quantumRootHash` and `quantumFileHash`; the anchor must come from the locally created test chain. It checks the main screen, persisted Quantum identity, 24-word custody and both protected role public keys. It does not deploy or broadcast a payment.

Authenticated balances/history, deployment/payment, backup and rescue UI, final artifacts and release acceptance remain required before declaring the default wallet ready.
