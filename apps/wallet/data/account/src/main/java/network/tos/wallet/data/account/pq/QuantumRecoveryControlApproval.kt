package network.tos.wallet.data.account.pq

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.util.UUID

/** Durable confirmation metadata only. Decoding never grants broadcast or permits a retry.
 * The future submission transition must consume a fresh native quote, not this record. */
internal class QuantumRecoveryControlApproval private constructor(
    val operation: UUID, messageHash: ByteArray, requestHash: ByteArray,
    val quotedAt: Long, val expiresAt: Long, val observedBalance: BigInteger,
    val estimatedBalance: BigInteger, val maximumEstimatedFee: BigInteger,
) {
    private val message = messageHash.copyOf()
    private val request = requestHash.copyOf()
    val estimatedFee: BigInteger = observedBalance - estimatedBalance
    init {
        require(message.size == 32 && request.size == 32)
        require(quotedAt in 1..0xffffffffL && expiresAt in 1..0xffffffffL && expiresAt - quotedAt in 1..30)
        for (amount in listOf(observedBalance, estimatedBalance, maximumEstimatedFee)) {
            require(amount.signum() >= 0 && amount.bitLength() <= 120)
        }
        require(maximumEstimatedFee.signum() > 0 && estimatedFee.signum() >= 0 && estimatedFee <= maximumEstimatedFee)
    }
    fun requireBound(entry: QuantumRecoveryControlJournal.Entry) {
        require(entry.operation == operation && entry.requestHash.contentEquals(request) && expiresAt <= entry.deadline &&
            QuantumPaymentOutbox.digest(entry.signedMessage()).contentEquals(message)) { "Recovery approval binding mismatch" }
    }
    private fun encode(): ByteArray = ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).use { out ->
            out.writeInt(MAGIC); out.writeLong(operation.mostSignificantBits); out.writeLong(operation.leastSignificantBits)
            out.write(message); out.write(request); out.writeLong(quotedAt); out.writeLong(expiresAt)
            for (amount in listOf(observedBalance, estimatedBalance, maximumEstimatedFee)) {
                val bytes = amount.toByteArray(); require(bytes.size <= 16)
                out.write(ByteArray(16 - bytes.size)); out.write(bytes)
            }
        }
    }.toByteArray().also { check(it.size == SIZE) }
    companion object {
        private const val MAGIC = 0x51434131
        private const val SIZE = 148
        fun record(operation: UUID, messageHash: ByteArray, requestHash: ByteArray, quotedAt: Long, expiresAt: Long,
                   observedBalance: BigInteger, estimatedBalance: BigInteger, maximumEstimatedFee: BigInteger): ByteArray =
            QuantumRecoveryControlApproval(operation, messageHash, requestHash, quotedAt, expiresAt,
                observedBalance, estimatedBalance, maximumEstimatedFee).encode()
        fun decode(bytes: ByteArray): QuantumRecoveryControlApproval {
            require(bytes.size == SIZE) { "Recovery approval length" }
            return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readInt() == MAGIC)
                val operation = UUID(input.readLong(), input.readLong())
                fun fixed(size: Int) = ByteArray(size).also(input::readFully)
                val message = fixed(32); val request = fixed(32)
                val quoted = input.readLong(); val expires = input.readLong()
                val result = QuantumRecoveryControlApproval(operation, message, request, quoted, expires,
                    BigInteger(1, fixed(16)), BigInteger(1, fixed(16)), BigInteger(1, fixed(16)))
                require(input.read() == -1)
                result
            }
        }
    }
}
