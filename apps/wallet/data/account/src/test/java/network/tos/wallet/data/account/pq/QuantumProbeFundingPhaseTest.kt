package network.tos.wallet.data.account.pq

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.UUID

/** Envelope/phase tests only; an opaque payload never simulates authenticated restore. */
class QuantumProbeFundingPhaseTest {
    private fun envelope(phase: Int? = null, signature: ByteArray = ByteArray(0)): ByteArray =
        ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
            val id = UUID(1, 2)
            out.writeInt(if (phase == null) 0x51504631 else 0x51504632)
            out.writeLong(id.mostSignificantBits); out.writeLong(id.leastSignificantBits)
            if (phase != null) out.writeByte(phase)
            out.writeInt(1); out.writeByte(7)
            if (phase != null) { out.writeInt(signature.size); out.write(signature) }
        } }.toByteArray()
    @Test fun oldReservedEncodingIsRetainedForExactCompareAndUpdate() {
        val old = envelope()
        val decoded = QuantumProbeFundingJournal.Reservation.decode(old)
        assertEquals(QuantumProbeFundingJournal.Phase.RESERVED, decoded.phase)
        assertArrayEquals(old, decoded.encode())
        old.fill(0)
        assertArrayEquals(envelope(), decoded.encode())
    }
    @Test fun phasesCannotSkipOrRestartSigning() {
        val reserved = QuantumProbeFundingJournal.Reservation.decode(envelope())
        assertThrows(IllegalStateException::class.java) { reserved.finishSigning(ByteArray(7856)) }
        val signing = reserved.beginSigning()
        assertThrows(IllegalStateException::class.java) { signing.beginSigning() }
        val signature = ByteArray(7856) { 9 }
        val signed = signing.finishSigning(signature); signature.fill(0)
        assertEquals(reserved.operation, signed.operation)
        assertEquals(QuantumProbeFundingJournal.Phase.SIGNED,
            QuantumProbeFundingJournal.Reservation.decode(signed.encode()).phase)
        assertThrows(IllegalStateException::class.java) { signed.beginSigning() }
        assertThrows(IllegalStateException::class.java) { signed.finishSigning(ByteArray(7856)) }
    }
    @Test fun rejectsUnknownPhaseAndInconsistentSignatureShapes() {
        for ((phase, size) in listOf(3 to 0, 0 to 7856, 1 to 7856, 2 to 0, 2 to 7855, 2 to 7857)) {
            assertThrows(Exception::class.java) {
                QuantumProbeFundingJournal.Reservation.decode(envelope(phase, ByteArray(size)))
            }
        }
    }
    @Test fun rejectsTruncationAndTrailingBytes() {
        val valid = envelope(0)
        for (length in valid.indices) assertThrows(Exception::class.java) {
            QuantumProbeFundingJournal.Reservation.decode(valid.copyOf(length))
        }
        assertThrows(IllegalArgumentException::class.java) {
            QuantumProbeFundingJournal.Reservation.decode(valid + byteArrayOf(0))
        }
    }
}
