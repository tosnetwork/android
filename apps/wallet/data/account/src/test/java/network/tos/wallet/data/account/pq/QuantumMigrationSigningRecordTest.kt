package network.tos.wallet.data.account.pq

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Structural envelope only; public synthetic bindings/signatures never stand in for native proof. */
class QuantumMigrationSigningRecordTest {
    @Test fun submissionVersionCannotMasqueradeAsAnUnsignedOrReusableRecord() {
        val signed = QuantumMigrationSigningRecord.reserve(byteArrayOf(1)).beginSigning().finishSigning(ByteArray(7856) { 7 })
        val approval = QuantumMigrationApproval.record(signed.operation, ByteArray(32) { 3 }, ByteArray(32) { 4 },
            1000, 1030, java.math.BigInteger.valueOf(100), java.math.BigInteger.valueOf(90), java.math.BigInteger.TEN)
        val wire = signed.beginSubmission(approval).encode()
        assertEquals(0x514d5332, ByteBuffer.wrap(wire).int)
        for (size in wire.indices) assertThrows(Exception::class.java) { QuantumMigrationSigningRecord.decode(wire.copyOf(size)) }
        assertThrows(IllegalArgumentException::class.java) { QuantumMigrationSigningRecord.decode(wire + 0.toByte()) }
        val oldMagic = wire.copyOf().also { ByteBuffer.wrap(it).putInt(0x514d5331) }
        assertThrows(IllegalArgumentException::class.java) { QuantumMigrationSigningRecord.decode(oldMagic) }
        val falseSigned = wire.copyOf().also { it[20] = 2 }
        assertThrows(IllegalArgumentException::class.java) { QuantumMigrationSigningRecord.decode(falseSigned) }
        assertEquals(0x514d5331, ByteBuffer.wrap(signed.encode()).int)
        assertArrayEquals(signed.encode(), QuantumMigrationSigningRecord.decode(signed.encode()).encode())
    }
    @Test fun restartRetainsSigningUncertaintyAndRejectsBackwardTransitions() {
        val reserved = QuantumMigrationSigningRecord.reserve(byteArrayOf(1, 2, 3))
        val signing = QuantumMigrationSigningRecord.decode(reserved.beginSigning().encode())
        assertEquals(reserved.operation, signing.operation)
        assertEquals(QuantumMigrationSigningRecord.Phase.SIGNING, signing.phase)
        assertThrows(IllegalStateException::class.java) { signing.beginSigning() }
        assertThrows(IllegalStateException::class.java) { signing.signature() }
        assertThrows(IllegalStateException::class.java) { reserved.finishSigning(ByteArray(7856)) }
        assertNull(signing.signatureDigest)
        val signed = QuantumMigrationSigningRecord.decode(signing.finishSigning(ByteArray(7856) { 7 }).encode())
        assertEquals(reserved.operation, signed.operation)
        assertThrows(IllegalStateException::class.java) { signed.beginSigning() }
        assertThrows(IllegalStateException::class.java) { signed.finishSigning(ByteArray(7856) { 8 }) }
    }
    @Test fun callerMutationCannotChangeRetainedBindingSignatureOrDigest() {
        val binding = byteArrayOf(1, 2, 3); val signature = ByteArray(7856) { 9 }
        val reserved = QuantumMigrationSigningRecord.reserve(binding)
        binding.fill(0); reserved.binding.fill(0)
        val signed = reserved.beginSigning().finishSigning(signature)
        val expected = MessageDigest.getInstance("SHA-256").digest(signature)
        signature.fill(0); signed.signature().fill(0); signed.signatureDigest!!.fill(0)
        assertArrayEquals(byteArrayOf(1, 2, 3), signed.binding)
        assertArrayEquals(expected, signed.signatureDigest)
        assertArrayEquals(signed.encode(), QuantumMigrationSigningRecord.decode(signed.encode()).encode())
    }
    @Test fun completeSigningBudgetIsReservedBeforeAnySignatureExists() {
        val maximum = QuantumMigrationSigningRecord.MAX_BINDING_BYTES
        val reserved = QuantumMigrationSigningRecord.reserve(ByteArray(maximum))
        val signed = reserved.beginSigning().finishSigning(ByteArray(7856))
        assertEquals(QuantumMigrationSigningRecord.MAX_SIGNING_BYTES, signed.encode().size)
        assertEquals(QuantumMigrationSigningRecord.MAX_FILE_BYTES,
            signed.encode().size + QuantumMigrationSigningRecord.RESERVED_METADATA_BYTES)
        assertThrows(IllegalArgumentException::class.java) { QuantumMigrationSigningRecord.reserve(ByteArray(maximum + 1)) }
        assertThrows(IllegalArgumentException::class.java) { QuantumMigrationSigningRecord.reserve(ByteArray(0)) }
        for (size in listOf(0, 7855, 7857)) assertThrows(IllegalArgumentException::class.java) {
            reserved.beginSigning().finishSigning(ByteArray(size))
        }
    }
    @Test fun malformedLengthsPhaseSignatureAndTrailingDataCannotReopenSigning() {
        val reserved = QuantumMigrationSigningRecord.reserve(byteArrayOf(1, 2, 3)).encode()
        val signed = QuantumMigrationSigningRecord.decode(reserved).beginSigning().finishSigning(ByteArray(7856)).encode()
        for (valid in listOf(reserved, signed)) {
            for (size in valid.indices) assertThrows(Exception::class.java) {
                QuantumMigrationSigningRecord.decode(valid.copyOf(size))
            }
            assertThrows(IllegalArgumentException::class.java) { QuantumMigrationSigningRecord.decode(valid + byteArrayOf(0)) }
        }
        for ((offset, value) in listOf(0 to 0, 21 to -1, 21 to Int.MAX_VALUE, 28 to 7856)) {
            val wrong = reserved.copyOf(); ByteBuffer.wrap(wrong).putInt(offset, value)
            assertThrows(Exception::class.java) { QuantumMigrationSigningRecord.decode(wrong) }
        }
        for (phase in listOf(2, 3, 255)) {
            val wrong = reserved.copyOf(); wrong[20] = phase.toByte()
            assertThrows(Exception::class.java) { QuantumMigrationSigningRecord.decode(wrong) }
        }
        val zeroId = reserved.copyOf(); zeroId.fill(0, 4, 20)
        assertThrows(IllegalArgumentException::class.java) { QuantumMigrationSigningRecord.decode(zeroId) }
    }
}
