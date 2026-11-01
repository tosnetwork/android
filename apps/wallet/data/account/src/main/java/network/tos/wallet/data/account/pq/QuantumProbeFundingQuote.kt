package network.tos.wallet.data.account.pq

import network.tos.security.pq.QuantumLocalSimulation
import org.json.JSONObject
import org.ton.boc.BagOfCells
import java.math.BigInteger
import java.util.UUID

/** Native local estimate only. This grants neither broadcast nor recovery-unlock authority. */
class QuantumProbeFundingQuote internal constructor(
    val operation: UUID,
    internal val plan: QuantumPreparedProbeFunding,
    message: ByteArray,
    private val simulation: QuantumLocalSimulation,
    request: ByteArray,
    private val quotedAt: Long,
    val maximumEstimatedFee: BigInteger,
) {
    private val messageBytes = message.copyOf()
    private val requestBytes = request.copyOf()
    val amount = plan.amount
    val address = plan.probeAddressText
    val expiresAt = minOf(plan.expiresAt, simulation.verifiedAt + 30)
    val observedBalance: BigInteger
    val estimatedBalance: BigInteger
    val estimatedDebit: BigInteger
    val estimatedFee: BigInteger
    init {
        require(maximumEstimatedFee.signum() > 0 && maximumEstimatedFee.bitLength() <= 120)
        plan.requireFresh(quotedAt)
        simulation.requireBinding(plan.profile.anchor(), requestBytes, messageBytes, quotedAt, 30)
        val amounts = validateExecution(JSONObject(simulation.encoded.toString(Charsets.UTF_8)), messageBytes,
            QuantumAccountReader.rawAddress(plan.identity), address, plan.probeBodyHash, amount)
        observedBalance = amounts.first; estimatedBalance = amounts.second
        estimatedDebit = observedBalance - estimatedBalance
        estimatedFee = estimatedDebit - amount
        check(estimatedFee <= maximumEstimatedFee) { "Probe funding estimate exceeds approved limit" }
        check(quotedAt < expiresAt)
    }
    internal fun requireFresh(currentOperation: UUID, currentPlan: QuantumPreparedProbeFunding,
                              currentMessage: ByteArray, now: Long) {
        check(currentOperation == operation && currentPlan.walletId == plan.walletId && currentPlan.identity == plan.identity &&
            sameProfile(currentPlan.profile, plan.profile) && currentPlan.request.digest.contentEquals(plan.request.digest) &&
            currentMessage.contentEquals(messageBytes)) { "Funding quote operation changed" }
        currentPlan.requireFresh(now)
        simulation.requireBinding(plan.profile.anchor(), requestBytes, messageBytes, now, 30)
        check(now < expiresAt)
    }
    companion object {
        /** Codec checks only; callers cannot mint a native simulation capability from JSON. */
        internal fun validateExecution(wire: JSONObject, message: ByteArray, source: String,
                                       destination: String, bodyHash: String, amount: BigInteger): Pair<BigInteger, BigInteger> {
            QuantumPreparedProbeFunding.requireAmount(amount)
            check(wire.getString("status") == "executed") { "Probe funding execution refused" }
            val account = wire.getJSONObject("account"); val tx = wire.getJSONObject("transaction")
            val codec = tx.getJSONObject("codec"); val compute = codec.getJSONObject("compute")
            val action = codec.getJSONObject("action"); val inbound = codec.getJSONObject("in_msg")
            val root = QuantumPaymentQuote.hex(BagOfCells(message).roots.single().hash().toByteArray())
            check(account.getBoolean("exists") && account.getString("address") == source && codec.getString("account") == source &&
                wire.getString("message_root_hash") == root &&
                wire.getString("message_bytes_hash") == QuantumPaymentQuote.hex(QuantumPaymentOutbox.digest(message)))
            check(codec.getString("type") == "ordinary" && !codec.getBoolean("aborted") && !codec.getBoolean("destroyed") &&
                !codec.getBoolean("other_fee_currencies") && !compute.getBoolean("skipped") && compute.getBoolean("success") &&
                compute.getInt("exit_code") in 0..1 && action.getBoolean("success") && action.getBoolean("valid") &&
                !action.getBoolean("no_funds") && action.getInt("result_code") == 0 && action.getInt("skipped_actions") == 0 &&
                action.getInt("messages_created") == 1 && inbound.getString("kind") == "external_in" &&
                inbound.getString("destination") == source && inbound.getString("hash") == root)
            val outputs = codec.getJSONArray("out_msgs"); check(outputs.length() == 1)
            val output = outputs.getJSONObject(0)
            check(output.getString("kind") == "internal" && output.getString("source") == source &&
                output.getString("destination") == destination && !output.getBoolean("bounced") && !output.getBoolean("bounce") &&
                !output.getBoolean("other_currencies") && QuantumPaymentQuote.coins(QuantumPaymentQuote.string(output, "value")) == amount &&
                output.getString("body_hash") == bodyHash) { "Probe funding output differs from reviewed request" }
            val before = QuantumPaymentQuote.coins(QuantumPaymentQuote.string(account, "observed_balance"))
            val after = QuantumPaymentQuote.coins(QuantumPaymentQuote.string(tx, "expected_balance"))
            val transactionFee = QuantumPaymentQuote.coins(QuantumPaymentQuote.string(codec, "fees"))
            // Debit includes forwarding costs as well as transaction fees; count all of it in the fee cap.
            check(before >= after && before - after >= amount && before - after - amount >= transactionFee)
            return before to after
        }
    }
}
