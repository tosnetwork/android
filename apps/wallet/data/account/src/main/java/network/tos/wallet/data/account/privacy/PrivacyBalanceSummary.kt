package network.tos.wallet.data.account.privacy

import org.json.JSONObject
import org.json.JSONTokener
import java.math.BigInteger

/** Native complete-ledger observation. Neither local reservations nor spending authority are represented. */
data class PrivacyBalanceSummary internal constructor(
    val totalOutputs: String, val ownedOutputs: String, val uniqueUnspentNotes: String,
    val duplicateOutputs: String, val reusedIndicesCount: String, val diagnosticsCount: String,
    val unspentBalance: String, val scanNextIndex: String?,
) {
    companion object {
        private val u64 = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)
        private val u128 = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE)
        fun parse(bytes: ByteArray): PrivacyBalanceSummary {
            require(bytes.size in 1..4096)
            val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            val reader = JSONTokener(text)
            val value = reader.nextValue() as? JSONObject ?: error("Privacy summary must be an object")
            require(reader.nextClean() == '\u0000') { "Trailing privacy summary" }
            require(value.keys().asSequence().toSet() == setOf("version", "total_outputs", "owned_outputs",
                "unique_unspent_notes", "duplicate_outputs", "reused_indices_count", "diagnostics_count",
                "unspent_balance", "scan_next_index")) { "Unexpected privacy summary fields" }
            require(value.get("version") == 1)
            fun decimal(name: String, maximum: BigInteger = u64): String {
                val result = value.get(name)
                require(result is String && result.length <= 39 && result.matches(Regex("0|[1-9][0-9]*"))) {
                    "Privacy summary requires canonical decimal strings"
                }
                require(BigInteger(result) <= maximum) { "Privacy summary integer overflow" }
                return result
            }
            val total = decimal("total_outputs", BigInteger.valueOf(32768))
            return PrivacyBalanceSummary(total, decimal("owned_outputs"), decimal("unique_unspent_notes"),
                decimal("duplicate_outputs"), decimal("reused_indices_count"), decimal("diagnostics_count"),
                decimal("unspent_balance", u128), if (value.isNull("scan_next_index")) null else decimal("scan_next_index"))
        }
    }
}

sealed class PrivacyBalanceState {
    data object NotConfigured : PrivacyBalanceState()
    data object MaterialUnavailable : PrivacyBalanceState()
    data object WalletNotProvisioned : PrivacyBalanceState()
    data object Loading : PrivacyBalanceState()
    data object Unverified : PrivacyBalanceState()
    data class Failed(val reason: Failure) : PrivacyBalanceState()
    enum class Failure { VERIFICATION_OR_TRANSPORT, BUSY }
    data class Observed internal constructor(val summary: PrivacyBalanceSummary, val pool: String,
        val globalId: Int, val provenShardTime: Long, val checkedAt: Long,
        val configurationSource: String = "Explicit local deployment pins") : PrivacyBalanceState()
}

internal class PrivacyMasterUnavailable : IllegalStateException("Original TOS master is unavailable")
