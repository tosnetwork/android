package network.tos.wallet.data.account.pq

import kotlinx.coroutines.CancellationException
import network.tos.security.privacy.PrivacyNative
import network.tos.security.privacy.PrivacyNative.Replay.PendingStatus
import network.tos.wallet.data.account.privacy.PrivacyBalanceState
import network.tos.wallet.data.account.privacy.PrivacyBalanceSummary
import network.tos.wallet.data.account.privacy.PrivacyPoolSelection

/** Uses only the existing proof and transaction transports. No note state or new request is written. */
internal class PrivacyBalanceReader(private val accounts: QuantumAccountReader, private val query: QuantumHistoryReader.Query,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val nanoTime: () -> Long = System::nanoTime,
) {
    fun read(record: QuantumCreationRecord, selection: PrivacyPoolSelection,
             stopped: () -> Boolean, master: ((ByteArray) -> PrivacyBalanceSummary) -> PrivacyBalanceSummary): PrivacyBalanceState.Observed =
        withReplay(record, selection, stopped, forReceive = false) { observation, completed, running ->
            val summary = master { seed ->
                val bytes = completed.summaryAndWipeSeed(seed, now, stopped = { running(); false })
                try { PrivacyBalanceSummary.parse(bytes) } finally { bytes.fill(0) }
            }
            check(summary.diagnosticsCount == "0") { "Privacy note scan reported diagnostics" }
            running()
            PrivacyBalanceState.Observed(summary, selection.pool, record.profile.globalId,
                observation.provenShardTime(now()), now())
        }

    fun receive(record: QuantumCreationRecord, selection: PrivacyPoolSelection, root: ByteArray,
                stopped: () -> Boolean, master: ((ByteArray) -> ByteArray) -> ByteArray): ByteArray =
        withReplay(record, selection, stopped, forReceive = true) { _, completed, running ->
            master { seed -> completed.receiveAndWipeSeed(seed, root, now) { running(); false } }
        }

    fun depositValue(record: QuantumCreationRecord, selection: PrivacyPoolSelection,
                     amount: ULong, stopped: () -> Boolean): ULong =
        withReplay(record, selection, stopped, forReceive = true) { _, completed, running ->
            completed.depositValue(amount, now, running)
        }

    fun prepareSelfDeposit(record: QuantumCreationRecord, selection: PrivacyPoolSelection, root: ByteArray,
                           amount: ULong, attachedValue: ULong, queryId: ULong, stopped: () -> Boolean,
                           master: ((ByteArray) -> ByteArray) -> ByteArray): ByteArray =
        withReplay(record, selection, stopped, forReceive = true) { _, completed, running ->
            master { seed ->
                completed.selfDepositAndWipeSeed(seed, root, amount, attachedValue, queryId, now, running)
            }
        }

    fun pendingStatus(record: QuantumCreationRecord, selection: PrivacyPoolSelection, root: ByteArray,
                      stopped: () -> Boolean,
                      master: ((ByteArray) -> PendingStatus) -> PendingStatus): PendingStatus =
        withReplay(record, selection, stopped, forReceive = true) { _, completed, running ->
            master { seed -> completed.pendingStatusAndWipeSeed(seed, root, now) { running(); false } }
        }

    fun validateRecipient(record: QuantumCreationRecord, selection: PrivacyPoolSelection,
                          descriptor: ByteArray, stopped: () -> Boolean): Boolean =
        withReplay(record, selection, stopped, forReceive = false) { _, completed, running ->
            val valid = completed.validateRecipient(descriptor, now)
            running()
            valid
        }

    fun backupFile(record: QuantumCreationRecord, selection: PrivacyPoolSelection, root: ByteArray,
                   fd: Int, restore: Boolean, stopped: () -> Boolean,
                   master: ((ByteArray) -> Unit) -> Unit) =
        withReplay(record, selection, stopped, forReceive = true) { _, completed, running ->
            master { seed -> completed.backupFileAndWipeSeed(seed, root, fd, restore, now, running) }
        }

    fun loadPaymentProver(
        record: QuantumCreationRecord, selection: PrivacyPoolSelection,
        phase1: java.io.File, ceremony: java.io.File, stopped: () -> Boolean,
    ): PrivacyNative.PaymentProver =
        withReplay(record, selection, stopped, forReceive = true) { _, completed, running ->
            val prover = completed.loadPaymentProver(phase1, ceremony, now)
            try {
                running()
                prover
            } catch (failure: Throwable) {
                prover.close()
                throw failure
            }
        }

    fun requestRelayOffer(
        record: QuantumCreationRecord, selection: PrivacyPoolSelection,
        approvedRelay: ByteArray, stopped: () -> Boolean,
    ): PrivacyNative.RelayOffer =
        withReplay(record, selection, stopped, forReceive = true) { _, completed, running ->
            val offer = completed.requestRelayOffer(approvedRelay, now)
            try {
                running()
                offer
            } catch (failure: Throwable) {
                offer.close()
                throw failure
            }
        }

    fun withdrawalFee(record: QuantumCreationRecord, selection: PrivacyPoolSelection,
                      stopped: () -> Boolean): String =
        withReplay(record, selection, stopped, forReceive = true) { _, completed, running ->
            running()
            org.json.JSONObject(completed.info(now()).decodeToString()).getString("withdrawal_fee")
        }

    fun stagePayment(
        record: QuantumCreationRecord, selection: PrivacyPoolSelection, root: ByteArray,
        recipient: ByteArray, amount: String, fee: String, quoteIdentity: String,
        prover: PrivacyNative.PaymentProver, offer: PrivacyNative.RelayOffer,
        stopped: () -> Boolean,
        withdrawalFee: String? = null,
        master: ((ByteArray) -> PrivacyNative.PaymentSubmission) -> PrivacyNative.PaymentSubmission,
    ): PrivacyNative.PaymentSubmission =
        withReplay(record, selection, stopped, forReceive = true) { _, completed, running ->
            master { seed ->
                try {
                    running()
                    completed.stageConfirmedPaymentAndWipeSeed(
                        seed, java.io.File(root.decodeToString()), recipient, amount, fee,
                        quoteIdentity, prover, offer, now, withdrawalFee,
                    )
                } finally { seed.fill(0) }
            }
        }

    fun recoverPayment(
        record: QuantumCreationRecord, selection: PrivacyPoolSelection, request: ByteArray,
        stopped: () -> Boolean,
        master: ((ByteArray) -> PrivacyNative.PaymentSubmission?) -> PrivacyNative.PaymentSubmission?,
    ): PrivacyNative.PaymentSubmission? =
        withReplay(record, selection, stopped, forReceive = true) { _, completed, running ->
            master { seed ->
                try {
                    running()
                    completed.recoverPaymentAndWipeSeed(seed, request, now)
                } finally { seed.fill(0) }
            }
        }

    private fun <T> withReplay(
        record: QuantumCreationRecord,
        selection: PrivacyPoolSelection,
        stopped: () -> Boolean,
        forReceive: Boolean,
        action: (QuantumAccountReader.PoolObservation, PrivacyHistorySynchronizer.Completed, () -> Unit) -> T,
    ): T {
        selection.requireProfile(record.profile)
        val started = nanoTime()
        fun running() {
            if (stopped() || Thread.currentThread().isInterrupted || nanoTime() - started >= 600_000_000_000L) {
                throw CancellationException("Privacy observation cancelled or expired")
            }
        }
        // Hold the existing bounded lease even while acquiring the initial pool proof.
        PrivacyHistorySynchronizer.Lease(record.id).use { lease ->
            running()
            val observation = accounts.observePool(record.id, record.identity, record.profile, selection.address(),
                selection.expectedCode(), selection.profileBytes(), selection.manifestBytes(), selection.verifyingKeyBytes())
            running()
            PrivacyHistorySynchronizer.synchronize(
                observation, query, { running(); false }, now, lease, forReceive,
            ).use { completed ->
                running()
                return action(observation, completed, ::running)
            }
        }
    }
}
