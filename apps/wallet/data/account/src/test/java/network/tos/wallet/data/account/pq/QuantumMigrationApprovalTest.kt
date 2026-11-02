package network.tos.wallet.data.account.pq

import java.math.BigInteger
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Public codec only, never a fresh quote or dispatch capability. */
class QuantumMigrationApprovalTest {
    private val operation = UUID(1, 2)
    private fun record(before: BigInteger = BigInteger.valueOf(100), after: BigInteger = BigInteger.valueOf(90),
        cap: BigInteger = BigInteger.TEN, quoted: Long = 1000, expires: Long = 1030) =
        QuantumMigrationApproval.record(operation, ByteArray(32) { 3 }, ByteArray(32) { 4 }, quoted, expires, before, after, cap)

    @Test fun exactBindingAndDefensiveDecodeRejectSubstitution() {
        val bytes = record(); assertEquals(148, bytes.size)
        val saved = QuantumMigrationApproval.decode(bytes); bytes.fill(0)
        assertEquals(BigInteger.TEN, saved.estimatedFee)
        saved.requireBound(operation, ByteArray(32) { 3 }, ByteArray(32) { 4 }, 1120)
        assertThrows(IllegalArgumentException::class.java) { saved.requireBound(UUID(2, 3), ByteArray(32) { 3 }, ByteArray(32) { 4 }, 1120) }
        assertThrows(IllegalArgumentException::class.java) { saved.requireBound(operation, ByteArray(32) { 5 }, ByteArray(32) { 4 }, 1120) }
        assertThrows(IllegalArgumentException::class.java) { saved.requireBound(operation, ByteArray(32) { 3 }, ByteArray(32) { 5 }, 1120) }
        assertThrows(IllegalArgumentException::class.java) { saved.requireBound(operation, ByteArray(32) { 3 }, ByteArray(32) { 4 }, 1029) }
    }
    @Test fun feeAndUnsignedWireBoundsAreEnforced() {
        assertThrows(IllegalArgumentException::class.java) { record(cap = BigInteger.valueOf(9)) }
        assertThrows(IllegalArgumentException::class.java) { record(cap = BigInteger.ZERO) }
        assertThrows(IllegalArgumentException::class.java) { record(after = BigInteger.valueOf(101)) }
        assertThrows(IllegalArgumentException::class.java) { record(before = BigInteger.valueOf(-1)) }
        assertThrows(IllegalArgumentException::class.java) { record(before = BigInteger.ONE.shiftLeft(120)) }
        val maximum = BigInteger.ONE.shiftLeft(120) - BigInteger.ONE
        val saved = QuantumMigrationApproval.decode(record(before = maximum, after = maximum - BigInteger.TEN))
        assertEquals(maximum, saved.observedBalance)
    }
    @Test fun deadlineAndFramingAreStrict() {
        for ((quoted, expires) in listOf(0L to 1L, 1000L to 1000L, 1000L to 1031L,
            0xffffffffL to 0x100000000L)) assertThrows(IllegalArgumentException::class.java) { record(quoted = quoted, expires = expires) }
        val bytes = record()
        for (size in 0 until bytes.size) assertThrows(IllegalArgumentException::class.java) { QuantumMigrationApproval.decode(bytes.copyOf(size)) }
        assertThrows(IllegalArgumentException::class.java) { QuantumMigrationApproval.decode(bytes + 0.toByte()) }
        assertThrows(IllegalArgumentException::class.java) { QuantumMigrationApproval.decode(bytes.copyOf().also { it[3] = 0 }) }
        assertThrows(IllegalArgumentException::class.java) { QuantumMigrationApproval.decode(bytes.copyOf().also { it.fill(0, 4, 20) }) }
    }
}
