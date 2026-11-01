package network.tos.wallet.data.account

import androidx.test.ext.junit.runners.AndroidJUnit4
import network.tos.wallet.data.account.pq.QuantumRecoveryControlApproval
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigInteger
import java.nio.ByteBuffer
import java.util.UUID

/** Metadata codecs only; no native quote or signing/broadcast authority is manufactured here. */
@RunWith(AndroidJUnit4::class)
class QuantumRecoveryControlApprovalTest {
    private val operation = UUID.randomUUID()
    private fun record(before: BigInteger = BigInteger("100"), after: BigInteger = BigInteger("90"),
                       limit: BigInteger = BigInteger("10"), start: Long = 1000, end: Long = 1030) =
        QuantumRecoveryControlApproval.record(operation, ByteArray(32) { 1 }, ByteArray(32) { 2 }, start, end, before, after, limit)

    @Test fun fixedMetadataRetainsFullUnsignedValuesAndTimeBounds() {
        val maximum = BigInteger.ONE.shiftLeft(120).subtract(BigInteger.ONE)
        val bytes = record(maximum, maximum - BigInteger.TEN)
        assertEquals(148, bytes.size)
        val value = QuantumRecoveryControlApproval.decode(bytes)
        assertEquals(operation, value.operation); assertEquals(maximum, value.observedBalance)
        assertEquals(BigInteger.TEN, value.estimatedFee); assertEquals(1030L, value.expiresAt)
        assertThrows(IllegalArgumentException::class.java) { record(end = 1031) }
        assertThrows(IllegalArgumentException::class.java) { record(end = 1000) }
        assertThrows(IllegalArgumentException::class.java) { record(start = 0, end = 1) }
        assertThrows(IllegalArgumentException::class.java) { record(start = 0xffffffffL, end = 0x100000000L) }
    }

    @Test fun alteredAmountsTruncationTailAndUnknownVersionFailClosed() {
        val bytes = record()
        assertThrows(IllegalArgumentException::class.java) { QuantumRecoveryControlApproval.decode(bytes.copyOf(bytes.size - 1)) }
        assertThrows(IllegalArgumentException::class.java) { QuantumRecoveryControlApproval.decode(bytes + byteArrayOf(0)) }
        assertThrows(IllegalArgumentException::class.java) { QuantumRecoveryControlApproval.decode(bytes.copyOf().also { it[3] = 2 }) }
        // Fixed field offsets: header/UUID/digests/times = 100 bytes, then 3 unsigned 128-bit fields with 120-bit limit.
        assertThrows(IllegalArgumentException::class.java) { QuantumRecoveryControlApproval.decode(bytes.copyOf().also { it[100] = 1 }) }
        assertThrows(IllegalArgumentException::class.java) {
            QuantumRecoveryControlApproval.decode(bytes.copyOf().also { ByteBuffer.wrap(it).putLong(92, 1031) })
        }
        assertThrows(IllegalArgumentException::class.java) { record(after = BigInteger("101")) }
        assertThrows(IllegalArgumentException::class.java) { record(limit = BigInteger("9")) }
        assertThrows(IllegalArgumentException::class.java) { record(limit = BigInteger.ZERO) }
        assertThrows(IllegalArgumentException::class.java) { record(before = BigInteger.ONE.shiftLeft(120)) }
    }
}
