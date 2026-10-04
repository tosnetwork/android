package network.tos.wallet.api.tos

import org.json.JSONObject
import org.ton.block.AddrStd
import java.math.BigInteger

/** Reconciliation trusts the selected node, but never treats a counter or a
 * broadcast acknowledgement as delivery. Every hop must bind the original hash. */
object TosPqReceipt {
    enum class Status { PENDING, FEE_ACCEPTED, AUTH_ACCEPTED, DEPLOYED, DELIVERED, FAILED, EXPIRED }
    data class Intent(val feeAddress: String, val externalHash: String, val moduleAddress: String,
        val walletAddress: String, val recipient: String? = null, val amount: Long = 0)
    fun reconcile(intent: Intent, history: (String) -> List<JSONObject>, deployed: () -> Boolean): Status {
        fun receipt(address: String, hash: String) = history(address).firstOrNull { it.opt("in_msg_hash") == hash }
        val fee = receipt(intent.feeAddress, intent.externalHash) ?: return Status.PENDING
        if (!successful(fee)) return if (rejected(fee)) Status.FAILED else Status.PENDING
        if (intent.recipient == null) return if (deployed()) Status.DEPLOYED else Status.FEE_ACCEPTED
        val moduleInput = outgoing(fee).singleOrNull { destination(it) == address(intent.moduleAddress) }
            ?: return Status.FEE_ACCEPTED
        val module = receipt(intent.moduleAddress, moduleInput.getString("hash")) ?: return Status.FEE_ACCEPTED
        if (!successful(module)) return if (rejected(module)) Status.FAILED else Status.FEE_ACCEPTED
        val walletInput = outgoing(module).singleOrNull { destination(it) == address(intent.walletAddress) }
            ?: return Status.FEE_ACCEPTED
        val wallet = receipt(intent.walletAddress, walletInput.getString("hash")) ?: return Status.AUTH_ACCEPTED
        if (!successful(wallet)) return if (rejected(wallet)) Status.FAILED else Status.AUTH_ACCEPTED
        val outputs = outgoing(wallet)
        require(outputs.size == 1) { "Unexpected PQ actions" }
        val sent = outputs.single()
        require(destination(sent) == address(intent.recipient) && sent.opt("kind") == "internal" &&
            sent.opt("bounced") == false && sent.getString("value").toBigInteger() == BigInteger.valueOf(intent.amount)) { "Unbound recipient/value" }
        val recipient = receipt(intent.recipient, sent.getString("hash")) ?: return Status.AUTH_ACCEPTED
        val incoming = recipient.getJSONObject("in_msg")
        require(destination(incoming) == address(intent.recipient) && incoming.opt("bounced") == false &&
            incoming.getString("value").toBigInteger() == BigInteger.valueOf(intent.amount))
        // A passive uninitialized account credits this exact message although
        // its compute phase is skipped. This does not claim contract execution.
        val compute = recipient.optJSONObject("compute")
        val passive = recipient.opt("transaction_type") == "ordinary" && recipient.opt("aborted") == true &&
            compute?.opt("skipped") == true && integer(compute.opt("skip_reason")) == 0L &&
            recipient.isNull("action") && outgoing(recipient).isEmpty() &&
            recipient.getString("fee").toBigInteger().let { it.signum() >= 0 && it < BigInteger.valueOf(intent.amount) }
        return if (successfulRecipient(recipient) || passive) Status.DELIVERED else if (rejected(recipient)) Status.FAILED else Status.AUTH_ACCEPTED
    }
    private fun integer(value: Any?): Long? = when(value) { is Int -> value.toLong();is Long -> value;else -> null }
    private fun rejected(tx: JSONObject) = tx.opt("aborted") == true && outgoing(tx).all { it.opt("bounced") == true }
    private fun address(raw: String) = AddrStd.parse(raw)
    private fun destination(message: JSONObject) = runCatching { address(message.getString("destination")) }.getOrNull()
    private fun outgoing(tx: JSONObject): List<JSONObject> {
        val array = tx.getJSONArray("out_msgs")
        return (0 until array.length()).map { array.getJSONObject(it) }
    }
    private fun successfulRecipient(tx: JSONObject): Boolean {
        val c = tx.optJSONObject("compute") ?: return false
        val a = tx.optJSONObject("action")
        return tx.opt("transaction_type") == "ordinary" && tx.opt("aborted") == false && c.opt("skipped") == false &&
            c.opt("success") == true && integer(c.opt("exit_code")) == 0L && (a == null ||
            (a.opt("success") == true && a.opt("valid") == true && a.opt("no_funds") == false && integer(a.opt("result_code")) == 0L && integer(a.opt("skipped_actions")) == 0L))
    }
    private fun successful(tx: JSONObject): Boolean {
        val a = tx.optJSONObject("action") ?: return false
        return successfulRecipient(tx) && integer(a.opt("messages_created")) == outgoing(tx).size.toLong() && outgoing(tx).isNotEmpty()
    }
}

fun TosSource.pqReconcile(intent: TosPqReceipt.Intent, testnet: Boolean = false,
    deployed: () -> Boolean): TosPqReceipt.Status = TosPqReceipt.reconcile(intent, { address ->
        val array = rpc.callArray("getTransactions", JSONObject().put("address", address).put("limit", 64), testnet)
        (0 until array.length()).map { array.getJSONObject(it) }
    }, deployed)
