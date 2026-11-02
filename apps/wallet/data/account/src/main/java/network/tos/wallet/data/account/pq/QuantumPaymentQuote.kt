package network.tos.wallet.data.account.pq

import network.tos.blockchain.ton.contract.QuantumPaymentIntent
import network.tos.security.pq.QuantumLocalSimulation
import org.json.JSONObject
import org.ton.boc.BagOfCells
import java.math.BigInteger
import java.util.UUID

/** Short-lived exact-message estimate minted only after durable native verification. */
class QuantumPaymentQuote internal constructor(
    internal val payment: QuantumPreparedPayment, private val entry: QuantumPaymentOutbox.Entry,
    private val simulation: QuantumLocalSimulation, private val quotedAt: Long,
) {
    val intent = payment.intent
    val operationId: UUID = entry.id
    val observedBalance: BigInteger
    val estimatedFee: BigInteger
    val estimatedDebit: BigInteger
    val estimatedBalance: BigInteger
    val expiresAt: Long = minOf(payment.expiresAt, simulation.verifiedAt + 30)
    private val message = checkNotNull(entry.signedBOC)
    init {
        payment.requireFresh(quotedAt); requireSameEntry(entry)
        simulation.requireBinding(payment.anchorBytes, payment.simulationRequest(), message, quotedAt, 30)
        val wire = JSONObject(simulation.encoded.toString(Charsets.UTF_8))
        check(wire.getString("status") == "executed") { "Payment cannot be executed" }
        val account = wire.getJSONObject("account"); val tx = wire.getJSONObject("transaction"); val codec = tx.getJSONObject("codec")
        val address = QuantumAccountReader.rawAddress(payment.identity)
        val root = hex(BagOfCells(message).roots.single().hash().toByteArray())
        check(account.getString("address") == address && codec.getString("account") == address && wire.getString("message_root_hash") == root)
        val compute = codec.getJSONObject("compute"); val action = codec.getJSONObject("action"); val inbound = codec.getJSONObject("in_msg")
        check(codec.getString("type") == "ordinary" && !codec.getBoolean("aborted") && !codec.getBoolean("destroyed") && !codec.getBoolean("other_fee_currencies") &&
            !compute.getBoolean("skipped") && compute.getBoolean("success") && compute.getInt("exit_code") in 0..1 &&
            action.getBoolean("success") && action.getBoolean("valid") && !action.getBoolean("no_funds") && action.getInt("result_code") == 0 &&
            action.getInt("skipped_actions") == 0 && action.getInt("messages_created") == 1 &&
            inbound.getString("kind") == "external_in" && inbound.getString("destination") == address && inbound.getString("hash") == root) { "Payment execution refused" }
        val outputs = codec.getJSONArray("out_msgs"); check(outputs.length() == 1)
        val out = outputs.getJSONObject(0)
        check(out.getString("kind") == "internal" && out.getString("source") == address &&
            out.getString("destination") == QuantumAccountReader.rawAddressFor(intent.recipient) &&
            !out.getBoolean("bounced") && out.getBoolean("bounce") == intent.bounce && !out.getBoolean("other_currencies") &&
            coins(string(out, "value")) == intent.amount && out.getString("body_hash") == hex(intent.body.hash().toByteArray())) { "Payment output differs from confirmation" }
        observedBalance = coins(string(account, "observed_balance")); estimatedBalance = coins(string(tx, "expected_balance"))
        check(observedBalance >= estimatedBalance)
        estimatedDebit = observedBalance - estimatedBalance
        check(estimatedDebit >= intent.amount)
        estimatedFee = estimatedDebit - intent.amount
        check(quotedAt < expiresAt)
    }
    internal fun requireSameEntry(current: QuantumPaymentOutbox.Entry) {
        check(current.id == operationId && current.identity == payment.identity && sameProfile(current.profile, payment.profile) &&
            current.requestHash.contentEquals(payment.request.digest) && current.intent == intent && current.deploy == payment.requiresDeployment &&
            current.phase in setOf(QuantumPaymentOutbox.Phase.SIGNED, QuantumPaymentOutbox.Phase.SUBMITTING, QuantumPaymentOutbox.Phase.UNKNOWN) &&
            current.signedBOC.contentEquals(message)) { "Quote authorization binding mismatch" }
    }
    internal fun requireFresh(current: QuantumPaymentOutbox.Entry, now: Long) {
        payment.requireFresh(now); requireSameEntry(current)
        simulation.requireBinding(payment.anchorBytes, payment.simulationRequest(), message, now, 30)
        check(now < expiresAt)
    }
    internal fun approval() = JSONObject().put("message_sha256", hex(QuantumPaymentOutbox.digest(message)))
        .put("observed_balance", observedBalance.toString()).put("estimated_fee", estimatedFee.toString())
        .put("estimated_debit", estimatedDebit.toString()).put("estimated_balance", estimatedBalance.toString())
        .put("quoted_at", quotedAt).put("expires_at", expiresAt)
    companion object {
        internal fun string(value: JSONObject, key: String): String = value.get(key) as? String ?: error("Expected exact string: $key")
        internal fun coins(text: String): BigInteger {
            require(text.length in 1..37 && text.matches(Regex("0|[1-9][0-9]*")))
            return BigInteger(text).also { require(it.bitLength() <= 120) }
        }
        internal fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun boundedTime(value: JSONObject, key: String): Long {
            val raw = value.get(key)
            check(raw is Int || raw is Long) { "Expected integer timestamp" }
            return (raw as Number).toLong().also { check(it in 1..0xffffffffL) }
        }
        internal fun requireApproval(value: JSONObject, message: ByteArray, intent: QuantumPaymentIntent) {
            check(string(value, "message_sha256") == hex(QuantumPaymentOutbox.digest(message)))
            val before = coins(string(value, "observed_balance")); val after = coins(string(value, "estimated_balance"))
            val debit = coins(string(value, "estimated_debit")); val fee = coins(string(value, "estimated_fee"))
            check(before >= after && before - after == debit && debit >= intent.amount && debit - intent.amount == fee)
            val time = boundedTime(value, "quoted_at"); val expiry = boundedTime(value, "expires_at")
            check(time in 1 until expiry && expiry <= 0xffffffffL && expiry - time <= 30)
        }
    }
}
