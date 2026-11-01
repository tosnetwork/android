package network.tos.wallet.data.account.pq

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.UUID
import java.math.BigInteger

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
    private fun approval(operation: UUID = UUID(1, 2)) = QuantumProbeFundingApproval.record(operation,
        ByteArray(32) { 1 }, ByteArray(32) { 2 }, 1000, 1020, BigInteger("100"),
        BigInteger("1000"), BigInteger("890"), BigInteger("20"))
    @Test fun submittingAndUnknownNeverRestoreSigningOrInitialDispatch() {
        val reserved = QuantumProbeFundingJournal.Reservation.decode(envelope())
        val signing = reserved.beginSigning()
        assertThrows(IllegalStateException::class.java) { reserved.beginSubmission(approval()) }
        assertThrows(IllegalStateException::class.java) { signing.beginSubmission(approval()) }
        val signed = signing.finishSigning(ByteArray(7856) { 9 })
        assertThrows(IllegalArgumentException::class.java) { signed.beginSubmission(approval(UUID(3, 4))) }
        val metadata = approval()
        val submitting = signed.beginSubmission(metadata)
        val encoded = submitting.encode(); metadata.fill(0)
        assertArrayEquals(encoded, submitting.encode())
        val restored = QuantumProbeFundingJournal.Reservation.decode(encoded)
        assertEquals(QuantumProbeFundingJournal.Phase.SUBMITTING, restored.phase)
        assertEquals(signed.signatureDigest, restored.signatureDigest)
        val unknown = restored.unknown()
        val restarted = QuantumProbeFundingJournal.Reservation.decode(unknown.encode())
        assertEquals(QuantumProbeFundingJournal.Phase.UNKNOWN, restarted.phase)
        assertEquals(signed.signatureDigest, restarted.signatureDigest)
        assertArrayEquals(restarted.encode(), restarted.unknown().encode())
        for (entry in listOf(restored, restarted)) {
            assertThrows(IllegalStateException::class.java) { entry.beginSigning() }
            assertThrows(IllegalStateException::class.java) { entry.finishSigning(ByteArray(7856)) }
            assertThrows(IllegalStateException::class.java) { entry.beginSubmission(approval()) }
        }
        for (length in listOf(0, 24, 28, encoded.size - 1)) assertThrows(Exception::class.java) {
            QuantumProbeFundingJournal.Reservation.decode(encoded.copyOf(length))
        }
        assertThrows(IllegalArgumentException::class.java) { QuantumProbeFundingJournal.Reservation.decode(encoded + byteArrayOf(0)) }
    }
    @Test fun approvalRejectsInvalidTimeFeeAndAmountAndRoundTripsExactValues() {
        val value = QuantumProbeFundingApproval.decode(approval())
        assertEquals(UUID(1, 2), value.operation)
        assertEquals(BigInteger.TEN, value.estimatedFee)
        assertEquals(BigInteger("100"), value.amount)
        fun record(time: Long = 1000, expiry: Long = 1020, amount: BigInteger = BigInteger("100"),
                   after: BigInteger = BigInteger("890"), cap: BigInteger = BigInteger("20")) =
            QuantumProbeFundingApproval.record(UUID(1, 2), ByteArray(32), ByteArray(32), time, expiry,
                amount, BigInteger("1000"), after, cap)
        assertThrows(IllegalArgumentException::class.java) { record(time = 0) }
        assertThrows(IllegalArgumentException::class.java) { record(expiry = 1031) }
        assertThrows(IllegalArgumentException::class.java) { record(amount = BigInteger.ZERO) }
        assertThrows(IllegalArgumentException::class.java) { record(amount = QuantumPreparedProbeFunding.MAX_FUNDING + BigInteger.ONE) }
        assertThrows(IllegalArgumentException::class.java) { record(after = BigInteger("901")) }
        assertThrows(IllegalArgumentException::class.java) { record(cap = BigInteger.valueOf(9)) }
        assertThrows(IllegalArgumentException::class.java) { QuantumProbeFundingApproval.decode(approval() + byteArrayOf(0)) }
        assertThrows(IllegalArgumentException::class.java) { QuantumProbeFundingApproval.decode(approval().copyOf(163)) }
    }
    @Test fun historicalApprovalRequiresExactMessageRequestOperationAmountAndDeadline() {
        val id = UUID(5, 6)
        val message = byteArrayOf(3, 7, 9)
        val request = ByteArray(32) { 2 }
        val amount = BigInteger("100")
        val approval = QuantumProbeFundingApproval.decode(QuantumProbeFundingApproval.record(id,
            QuantumPaymentOutbox.digest(message), request, 1000, 1020, amount,
            BigInteger("1000"), BigInteger("890"), BigInteger("20")))
        // Past approval remains inspectable; this API intentionally has no clock or dispatch path.
        approval.requireSubmitted(id, amount, 1020, request, message)
        assertThrows(IllegalArgumentException::class.java) {
            approval.requireSubmitted(UUID(7, 8), amount, 1020, request, message)
        }
        assertThrows(IllegalArgumentException::class.java) {
            approval.requireSubmitted(id, amount + BigInteger.ONE, 1020, request, message)
        }
        assertThrows(IllegalArgumentException::class.java) {
            approval.requireSubmitted(id, amount, 1019, request, message)
        }
        assertThrows(IllegalArgumentException::class.java) {
            approval.requireSubmitted(id, amount, 1020, request.copyOf().also { it[0] = 4 }, message)
        }
        assertThrows(IllegalArgumentException::class.java) {
            approval.requireSubmitted(id, amount, 1020, request, message + byteArrayOf(0))
        }
    }
}
