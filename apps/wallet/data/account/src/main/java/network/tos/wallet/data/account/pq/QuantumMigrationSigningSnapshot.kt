package network.tos.wallet.data.account.pq

import network.tos.blockchain.ton.contract.QuantumAuthRole
import network.tos.blockchain.ton.contract.QuantumStoredSelfPayRequest
import org.ton.boc.BagOfCells

/** Typed mobile MIGRATE snapshot. Neither decoding nor restoring grants a signer or dispatch.
 * Only a private fresh funded result plus the same confirmed backup may restore a live plan. */
internal class QuantumMigrationSigningSnapshot private constructor(private val binding: QuantumMigrationSigningBinding) {
    fun encode(): ByteArray = binding.encode()

    data class Saved(val requestHash: String, val deadline: Long, val backupDigest: ByteArray)

    /** Historical consistency/signature only. No fresh chain state, signer or dispatch capability. */
    fun inspectSaved(entry: QuantumProbeFundingJournal.Reservation, prior: QuantumRecoveryControlJournal.Entry,
        probe: QuantumRecoveryProbeJournal.Entry, signature: ByteArray?,
    ): Saved {
        binding.requireContext(entry.walletId, entry.operation, binding.backupDigest,
            entry.encode(), prior.encode(), probe.encode())
        check(entry.phase == QuantumProbeFundingJournal.Phase.COMPLETED)
        val submitted = entry.submission(prior, probe)
        val stored = QuantumStoredSelfPayRequest.parse(binding.request)
        stored.requireIdentity(prior.identity)
        require(stored.kind == 4 && stored.role == QuantumAuthRole.RESCUE &&
            submitted.expectedState.retired == 2 && probe.successor.policy == network.tos.blockchain.ton.contract.QuantumPolicy.REQUIRED)
        val expected = network.tos.blockchain.ton.contract.TosQuantumSelfPayRequest(prior.identity.address,
            submitted.expectedState, QuantumAuthRole.RESCUE, stored.deadline,
            network.tos.blockchain.ton.contract.QuantumSelfPayAction.Migrate(probe.successor), stored.deadline - 1)
        check(expected.request.hash() == stored.cell.hash()) { "Saved migration intent mismatch" }
        if (signature != null) check(network.tos.security.pq.QuantumCrypto.verify(
            network.tos.security.pq.QuantumRole.RESCUE, network.tos.security.pq.QuantumPurpose.SELF_PAY,
            submitted.expectedState.authority.rescueKey, expected.digest, signature)) { "Saved migration signature mismatch" }
        return Saved(expected.digest.joinToString("") { "%02x".format(it) }, stored.deadline, binding.backupDigest)
    }

    fun restore(entry: QuantumProbeFundingJournal.Reservation, prior: QuantumRecoveryControlJournal.Entry,
        probe: QuantumRecoveryProbeJournal.Entry, result: QuantumProbeFundingOutcomeReader.Result,
        confirmedBackupDigest: ByteArray, now: Long,
    ): QuantumPreparedSuccessorMigration {
        binding.requireContext(entry.walletId, entry.operation, confirmedBackupDigest, entry.encode(), prior.encode(), probe.encode())
        val stored = QuantumStoredSelfPayRequest.parse(binding.request)
        stored.requireIdentity(prior.identity)
        require(stored.kind == 4 && stored.role == QuantumAuthRole.RESCUE)
        // Reconstruct with the retained deadline, never now + TTL. Expired snapshots stay blocked.
        val plan = result.prepareMigration(entry, prior, probe, confirmedBackupDigest, now,
            QuantumMigrationDeadline.MOBILE_TTL, stored.deadline)
        check(plan.request.request.hash() == stored.cell.hash()) { "Migration request changed after reservation" }
        binding.requireIdentical(create(plan, entry, prior, probe, now).binding)
        return plan
    }

    companion object {
        fun create(plan: QuantumPreparedSuccessorMigration, entry: QuantumProbeFundingJournal.Reservation,
            prior: QuantumRecoveryControlJournal.Entry, probe: QuantumRecoveryProbeJournal.Entry, now: Long,
        ): QuantumMigrationSigningSnapshot {
            require(plan.validityWindowSeconds == QuantumMigrationDeadline.MOBILE_TTL)
            require(entry.walletId == plan.walletId && entry.operation == plan.fundingOperation && probe.operation == plan.probeOperation)
            plan.requireRecords(entry, prior, probe, plan.confirmedBackupDigest)
            plan.requireFresh(now)
            return QuantumMigrationSigningSnapshot(QuantumMigrationSigningBinding.pack(plan.walletId, entry.operation,
                plan.confirmedBackupDigest, entry.encode(), prior.encode(), probe.encode(), BagOfCells(plan.request.request).toByteArray()))
        }
        fun decode(bytes: ByteArray): QuantumMigrationSigningSnapshot =
            QuantumMigrationSigningSnapshot(QuantumMigrationSigningBinding.decode(bytes))
    }
}
