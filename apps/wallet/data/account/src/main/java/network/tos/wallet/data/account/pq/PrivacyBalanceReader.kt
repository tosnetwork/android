package network.tos.wallet.data.account.pq

import kotlinx.coroutines.CancellationException
import network.tos.wallet.data.account.privacy.PrivacyBalanceState
import network.tos.wallet.data.account.privacy.PrivacyBalanceSummary
import network.tos.wallet.data.account.privacy.PrivacyPoolSelection

/** Uses only the existing proof and transaction transports. No note state or new request is written. */
internal class PrivacyBalanceReader(private val accounts: QuantumAccountReader, private val query: QuantumHistoryReader.Query,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val nanoTime: () -> Long = System::nanoTime,
) {
    fun read(record: QuantumCreationRecord, selection: PrivacyPoolSelection,
             stopped: () -> Boolean, master: ((ByteArray) -> PrivacyBalanceSummary) -> PrivacyBalanceSummary): PrivacyBalanceState.Observed {
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
            PrivacyHistorySynchronizer.synchronize(observation, query, { running(); false }, now, lease).use { completed ->
                running()
                val summary = master { seed ->
                    val bytes = completed.summaryAndWipeSeed(seed, now, stopped = { running(); false })
                    try { PrivacyBalanceSummary.parse(bytes) } finally { bytes.fill(0) }
                }
                check(summary.diagnosticsCount == "0") { "Privacy note scan reported diagnostics" }
                running(); observation.requireFresh(now())
                return PrivacyBalanceState.Observed(summary, selection.pool, record.profile.globalId,
                    observation.provenShardTime(now()), now())
            }
        }
    }
}
