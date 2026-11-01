package network.tos.wallet.data.account.pq

import network.tos.security.pq.QuantumLocalSimulation
import org.json.JSONObject
import org.ton.boc.BagOfCells
import java.math.BigInteger

/** Local estimate of the exact persisted control, never a chain receipt or unlock grant. */
class QuantumRecoveryControlQuote internal constructor(
    internal val entry: QuantumRecoveryControlJournal.Entry,
    private val simulation: QuantumLocalSimulation,
    request: ByteArray,
    private val quotedAt: Long,
    val maximumEstimatedFee: BigInteger,
) {
    val operation = entry.operation
    val kind = entry.kind
    val requestHash = QuantumPaymentQuote.hex(entry.requestHash)
    private val message = entry.signedMessage()
    private val requestBytes = request.copyOf()
    val expiresAt = minOf(entry.deadline, simulation.verifiedAt + 30)
    val observedBalance: BigInteger
    val estimatedBalance: BigInteger
    val estimatedFee: BigInteger
    init {
        require(maximumEstimatedFee.signum() > 0 && maximumEstimatedFee.bitLength() <= 120)
        simulation.requireBinding(entry.profile.anchor(), requestBytes, message, quotedAt, 30)
        val wire = JSONObject(simulation.encoded.toString(Charsets.UTF_8))
        val amounts = validateExecution(wire, message, QuantumAccountReader.rawAddress(entry.identity))
        observedBalance = amounts.first; estimatedBalance = amounts.second
        estimatedFee = observedBalance - estimatedBalance
        check(estimatedFee <= maximumEstimatedFee) { "Control estimate exceeds approved limit" }
        check(quotedAt < expiresAt)
    }
    internal fun requireFresh(current: QuantumRecoveryControlJournal.Entry, now: Long) {
        check(current.operation == operation && current.walletId == entry.walletId && current.identity == entry.identity &&
            sameProfile(current.profile, entry.profile) && current.requestHash.contentEquals(entry.requestHash) &&
            current.signedMessage().contentEquals(message)) { "Control estimate operation mismatch" }
        simulation.requireBinding(entry.profile.anchor(), requestBytes, message, now, 30)
        check(now < expiresAt)
    }
    /** Persistence metadata for the future explicit-confirmation transition; not a fresh capability. */
    internal fun approvalRecord(): ByteArray = QuantumRecoveryControlApproval.record(operation,
        QuantumPaymentOutbox.digest(message), entry.requestHash, quotedAt, expiresAt,
        observedBalance, estimatedBalance, maximumEstimatedFee)
    companion object {
        /** Strict codec checks only. The constructor additionally requires a native simulation capability. */
        internal fun validateExecution(wire: JSONObject, message: ByteArray, address: String): Pair<BigInteger, BigInteger> {
            check(wire.getString("status") == "executed") { "Recovery control execution refused" }
            val account = wire.getJSONObject("account"); val transaction = wire.getJSONObject("transaction")
            val codec = transaction.getJSONObject("codec"); val compute = codec.getJSONObject("compute")
            val inbound = codec.getJSONObject("in_msg")
            val root = QuantumPaymentQuote.hex(BagOfCells(message).roots.single().hash().toByteArray())
            check(account.getBoolean("exists") && account.getString("address") == address && codec.getString("account") == address &&
                wire.getString("message_root_hash") == root && wire.getString("message_bytes_hash") == QuantumPaymentQuote.hex(QuantumPaymentOutbox.digest(message)))
            check(codec.getString("type") == "ordinary" && !codec.getBoolean("aborted") && !codec.getBoolean("destroyed") &&
                !codec.getBoolean("other_fee_currencies") && !compute.getBoolean("skipped") && compute.getBoolean("success") &&
                compute.getInt("exit_code") in 0..1 && inbound.getString("kind") == "external_in" &&
                inbound.getString("destination") == address && inbound.getString("hash") == root)
            check(codec.getJSONArray("out_msgs").length() == 0) { "Recovery control cannot transfer value" }
            if (!codec.isNull("action")) {
                val action = codec.getJSONObject("action")
                check(action.getBoolean("success") && action.getBoolean("valid") && !action.getBoolean("no_funds") &&
                    action.getInt("result_code") == 0 && action.getInt("skipped_actions") == 0 && action.getInt("messages_created") == 0)
            }
            val before = QuantumPaymentQuote.coins(QuantumPaymentQuote.string(account, "observed_balance"))
            val after = QuantumPaymentQuote.coins(QuantumPaymentQuote.string(transaction, "expected_balance"))
            check(before >= after && before - after == QuantumPaymentQuote.coins(QuantumPaymentQuote.string(codec, "fees"))) { "Recovery control fee accounting mismatch" }
            return before to after
        }
    }
}
