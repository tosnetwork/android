package network.tos.wallet.data.account.pq

import network.tos.blockchain.ton.contract.TosQuantumSelfPayIdentity
import org.json.JSONObject
import java.security.MessageDigest

/** Inert semantic fingerprints. No JSON or Cell reserialization is an authority boundary. */
internal class QuantumAuthorizationBinding private constructor(original: QuantumPaymentOutbox.Entry) {
    val identity: TosQuantumSelfPayIdentity = original.identity
    val operationId = original.id
    private val request = original.authorization()
    val role = request.role
    val deadline = request.deadline
    private val identityRoot = identity.cell.hash().toByteArray()
    private val profile = original.profile
    private val authority = request.authorityHash
    private val epoch = request.epoch
    private val nonce = request.nonce
    private val requestRoot = original.requestHash
    private val requestBytes = original.requestBOC
    private val publicKey = original.signingPublicKey
    private val signature = original.signature
    private val message = original.signedBOC
    private val phase = original.phase
    private val deploy = original.deploy
    private val intent = original.intent
    private val approval = original.feeApproval?.let { Approval.parse(it) }
    val hasExactMessage: Boolean = message != null
    fun requireCurrent(current: QuantumPaymentOutbox.Entry) {
        val parsed = current.authorization()
        check(current.id == operationId && equal(current.identity.cell.hash().toByteArray(), identityRoot) &&
            sameProfile(current.profile, profile) && parsed.role == role && parsed.deadline == deadline &&
            parsed.epoch == epoch && parsed.nonce == nonce && equal(parsed.authorityHash, authority) &&
            current.deploy == deploy && current.intent == intent && current.phase == phase &&
            equal(current.requestHash, requestRoot) && equal(current.requestBOC, requestBytes) &&
            equal(current.signingPublicKey, publicKey) && equal(current.signature, signature) && equal(current.signedBOC, message) &&
            current.feeApproval?.let { Approval.parse(it) } == approval) { "Authorization capability binding changed" }
    }
    /** Fixed typed schema: key order, Int/Long representation and legal identity BOC order do not matter. */
    private data class Approval(val messageHash: String, val before: String, val after: String,
                                val debit: String, val fee: String, val quotedAt: Long, val expiresAt: Long) {
        companion object {
            fun parse(value: JSONObject): Approval {
                fun text(key: String) = QuantumPaymentQuote.string(value, key)
                fun time(key: String): Long {
                    val number = value.get(key)
                    check(number is Int || number is Long)
                    return (number as Number).toLong().also { check(it in 1..0xffffffffL) }
                }
                return Approval(text("message_sha256"), text("observed_balance"), text("estimated_balance"),
                    text("estimated_debit"), text("estimated_fee"), time("quoted_at"), time("expires_at"))
            }
        }
    }
    companion object {
        fun capture(validatedEntry: QuantumPaymentOutbox.Entry) = QuantumAuthorizationBinding(validatedEntry)
        private fun equal(a: ByteArray?, b: ByteArray?) = if (a == null || b == null) a == null && b == null else MessageDigest.isEqual(a, b)
    }
}
