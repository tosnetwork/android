package network.tos.wallet.data.account.pq

import network.tos.security.pq.QuantumLocalSimulation
import org.json.JSONObject
import java.math.BigInteger

/** Native estimate of one retained MIGRATE signature. No dispatch, install or unlock authority. */
class QuantumMigrationQuote internal constructor(
    internal val plan: QuantumPreparedSuccessorMigration,
    private val record: QuantumMigrationSigningRecord,
    message: ByteArray, request: ByteArray,
    private val simulation: QuantumLocalSimulation,
    private val quotedAt: Long, val maximumEstimatedFee: BigInteger,
) {
    private val messageBytes = message.copyOf()
    private val requestBytes = request.copyOf()
    val operation = record.operation
    val requestHash = QuantumPaymentQuote.hex(plan.request.digest)
    val replacementAuthorityHash = QuantumPaymentQuote.hex(plan.successor.hash)
    val expiresAt = minOf(plan.expiresAt, simulation.verifiedAt + 30)
    val observedBalance: BigInteger
    val estimatedBalance: BigInteger
    val estimatedFee: BigInteger

    init {
        require(record.phase == QuantumMigrationSigningRecord.Phase.SIGNED)
        require(maximumEstimatedFee.signum() > 0 && maximumEstimatedFee.bitLength() <= 120)
        val signature = record.signature()
        try { check(plan.signedMessage(signature, quotedAt).contentEquals(messageBytes)) }
        finally { signature.fill(0) }
        simulation.requireBinding(plan.profile.anchor(), requestBytes, messageBytes, quotedAt, 30)
        // MIGRATE must execute successfully without outbound transfers, with exact fee accounting.
        val balances = QuantumRecoveryControlQuote.validateExecution(JSONObject(simulation.encoded.toString(Charsets.UTF_8)),
            messageBytes, QuantumAccountReader.rawAddress(plan.identity))
        observedBalance = balances.first; estimatedBalance = balances.second
        estimatedFee = observedBalance - estimatedBalance
        check(estimatedFee <= maximumEstimatedFee) { "Migration estimate exceeds approved limit" }
        check(quotedAt < expiresAt)
    }

    internal fun requireFresh(current: QuantumMigrationSigningRecord, now: Long) {
        check(current.encode().contentEquals(record.encode())) { "Migration estimate reservation changed" }
        plan.requireFresh(now)
        simulation.requireBinding(plan.profile.anchor(), requestBytes, messageBytes, now, 30)
        check(now < expiresAt) { "Migration estimate expired" }
    }

    internal fun approvalRecord(): ByteArray = QuantumMigrationApproval.record(operation,
        QuantumPaymentOutbox.digest(messageBytes), plan.request.digest, quotedAt, expiresAt,
        observedBalance, estimatedBalance, maximumEstimatedFee)

    internal fun requireSubmitting(current: QuantumMigrationSigningRecord, now: Long) {
        check(current.encode().contentEquals(record.beginSubmission(approvalRecord()).encode())) {
            "Migration submission differs from fee confirmation"
        }
        requireFresh(record, now)
    }
}
