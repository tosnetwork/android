package network.tos.wallet.data.account.pq

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.util.UUID

/** Saved confirmation metadata only. Reading this never permits dispatch or retry. */
internal class QuantumProbeFundingApproval private constructor(
    val operation: UUID, messageHash: ByteArray, requestHash: ByteArray,
    val quotedAt: Long, val expiresAt: Long, val amount: BigInteger,
    val observedBalance: BigInteger, val estimatedBalance: BigInteger, val maximumEstimatedFee: BigInteger,
) {
    private val message = messageHash.copyOf()
    private val request = requestHash.copyOf()
    val estimatedFee = observedBalance - estimatedBalance - amount
    init {
        require(message.size == 32 && request.size == 32)
        require(quotedAt in 1..0xffffffffL && expiresAt in 1..0xffffffffL && expiresAt - quotedAt in 1..30)
        QuantumPreparedProbeFunding.requireAmount(amount)
        for (value in listOf(observedBalance, estimatedBalance, maximumEstimatedFee)) {
            require(value.signum() >= 0 && value.bitLength() <= 120)
        }
        require(maximumEstimatedFee.signum() > 0 && estimatedFee.signum() >= 0 && estimatedFee <= maximumEstimatedFee)
    }
    internal fun requireBound(operation: UUID, plan: QuantumPreparedProbeFunding, signedMessage: ByteArray) {
        require(this.operation == operation && amount == plan.amount && expiresAt <= plan.expiresAt &&
            request.contentEquals(plan.request.digest) && message.contentEquals(QuantumPaymentOutbox.digest(signedMessage))) {
            "Funding approval binding mismatch"
        }
    }
    /** Historical binding only; does not check freshness or authorize a dispatch. */
    internal fun requireSubmitted(operation: UUID, amount: BigInteger, deadline: Long,
                                  requestHash: ByteArray, signedMessage: ByteArray) {
        require(this.operation == operation && this.amount == amount && expiresAt <= deadline &&
            request.contentEquals(requestHash) && message.contentEquals(QuantumPaymentOutbox.digest(signedMessage))) {
            "Submitted funding approval binding mismatch"
        }
    }
    private fun encode(): ByteArray = ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).use { output ->
            output.writeInt(MAGIC); output.writeLong(operation.mostSignificantBits); output.writeLong(operation.leastSignificantBits)
            output.write(message); output.write(request); output.writeLong(quotedAt); output.writeLong(expiresAt)
            for (value in listOf(amount, observedBalance, estimatedBalance, maximumEstimatedFee)) {
                val bytes = value.toByteArray(); require(bytes.size <= 16)
                output.write(ByteArray(16 - bytes.size)); output.write(bytes)
            }
        }
    }.toByteArray().also { check(it.size == SIZE) }
    companion object {
        private const val MAGIC = 0x51464131
        const val SIZE = 164
        fun record(operation: UUID, messageHash: ByteArray, requestHash: ByteArray, quotedAt: Long, expiresAt: Long,
                   amount: BigInteger, observedBalance: BigInteger, estimatedBalance: BigInteger, maximumEstimatedFee: BigInteger) =
            QuantumProbeFundingApproval(operation, messageHash, requestHash, quotedAt, expiresAt, amount,
                observedBalance, estimatedBalance, maximumEstimatedFee).encode()
        fun decode(bytes: ByteArray): QuantumProbeFundingApproval {
            require(bytes.size == SIZE)
            return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readInt() == MAGIC)
                val operation = UUID(input.readLong(), input.readLong())
                fun fixed(size: Int) = ByteArray(size).also(input::readFully)
                val message = fixed(32); val request = fixed(32)
                val quoted = input.readLong(); val expires = input.readLong()
                QuantumProbeFundingApproval(operation, message, request, quoted, expires, BigInteger(1, fixed(16)),
                    BigInteger(1, fixed(16)), BigInteger(1, fixed(16)), BigInteger(1, fixed(16))).also { require(input.read() == -1) }
            }
        }
    }
}
