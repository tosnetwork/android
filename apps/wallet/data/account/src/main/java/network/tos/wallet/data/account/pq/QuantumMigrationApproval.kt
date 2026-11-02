package network.tos.wallet.data.account.pq

import java.nio.ByteBuffer
import java.math.BigInteger
import java.util.UUID

/** Saved confirmation metadata only. Decoding never grants dispatch or permits retry. */
internal class QuantumMigrationApproval private constructor(
    val operation: UUID, messageHash: ByteArray, requestHash: ByteArray,
    val quotedAt: Long, val expiresAt: Long, val observedBalance: BigInteger,
    val estimatedBalance: BigInteger, val maximumEstimatedFee: BigInteger,
) {
    private val message = messageHash.copyOf()
    private val request = requestHash.copyOf()
    val estimatedFee = observedBalance - estimatedBalance
    init {
        require(operation != UUID(0, 0) && message.size == 32 && request.size == 32)
        require(quotedAt in 1..0xffffffffL && expiresAt in 1..0xffffffffL && expiresAt - quotedAt in 1..30)
        for (value in listOf(observedBalance, estimatedBalance, maximumEstimatedFee)) {
            require(value.signum() >= 0 && value.bitLength() <= 120)
        }
        require(maximumEstimatedFee.signum() > 0 && estimatedFee.signum() >= 0 && estimatedFee <= maximumEstimatedFee)
    }
    fun requireBound(operation: UUID, messageHash: ByteArray, requestHash: ByteArray, deadline: Long) {
        require(this.operation == operation && message.contentEquals(messageHash) && request.contentEquals(requestHash) &&
            expiresAt <= deadline) { "Migration approval binding mismatch" }
    }
    private fun encode(): ByteArray = ByteBuffer.allocate(SIZE).apply {
        putInt(MAGIC); putLong(operation.mostSignificantBits); putLong(operation.leastSignificantBits)
        put(message); put(request); putLong(quotedAt); putLong(expiresAt)
        for (value in listOf(observedBalance, estimatedBalance, maximumEstimatedFee)) {
            val bytes = value.toByteArray(); require(bytes.size <= 16)
            put(ByteArray(16 - bytes.size)); put(bytes)
        }
    }.array()
    companion object {
        private const val MAGIC = 0x514d4131 // QMA1
        const val SIZE = 148
        fun record(operation: UUID, messageHash: ByteArray, requestHash: ByteArray, quotedAt: Long, expiresAt: Long,
            observedBalance: BigInteger, estimatedBalance: BigInteger, maximumEstimatedFee: BigInteger): ByteArray =
            QuantumMigrationApproval(operation, messageHash, requestHash, quotedAt, expiresAt,
                observedBalance, estimatedBalance, maximumEstimatedFee).encode()
        fun decode(bytes: ByteArray): QuantumMigrationApproval {
            require(bytes.size == SIZE)
            val buffer = ByteBuffer.wrap(bytes.copyOf())
            require(buffer.int == MAGIC)
            val operation = UUID(buffer.long, buffer.long)
            fun fixed(size: Int) = ByteArray(size).also(buffer::get)
            val message = fixed(32); val request = fixed(32)
            val quotedAt = buffer.long; val expiresAt = buffer.long
            return QuantumMigrationApproval(operation, message, request, quotedAt, expiresAt,
                BigInteger(1, fixed(16)), BigInteger(1, fixed(16)), BigInteger(1, fixed(16)))
        }
    }
}
