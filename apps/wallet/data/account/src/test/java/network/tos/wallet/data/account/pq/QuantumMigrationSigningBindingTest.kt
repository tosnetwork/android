package network.tos.wallet.data.account.pq

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.UUID

/** Public synthetic bytes only. These tests never fabricate a native authenticated plan. */
class QuantumMigrationSigningBindingTest {
    private val wallet = UUID(1, 2)
    private val operation = UUID(3, 4)
    private val backup = ByteArray(32) { 5 }
    private val funding = byteArrayOf(1, 2, 3)
    private val prior = byteArrayOf(4)
    private val probe = byteArrayOf(5)
    private val request = byteArrayOf(6)
    private fun binding(request: ByteArray = this.request) = QuantumMigrationSigningBinding.pack(
        wallet, operation, backup, funding, prior, probe, request)

    @Test fun bindingRoundTripAndCallerMutationDoNotChangeSavedOriginals() {
        val backup = this.backup.copyOf(); val funding = this.funding.copyOf()
        val value = QuantumMigrationSigningBinding.pack(wallet, operation, backup, funding, prior, probe, request)
        val bytes = value.encode(); backup.fill(0); funding.fill(0)
        value.backupDigest.fill(0); value.request.fill(0); value.funding.fill(0)
        val decoded = QuantumMigrationSigningBinding.decode(bytes)
        bytes.fill(0)
        value.requireIdentical(decoded)
        decoded.requireContext(wallet, operation, this.backup, this.funding, prior, probe)
        assertArrayEquals(request, decoded.request)
    }

    @Test fun differentWalletOperationBackupOrOriginalRecordIsRejected() {
        val value = binding()
        assertThrows(IllegalStateException::class.java) { value.requireContext(UUID(9, 9), operation, backup, funding, prior, probe) }
        assertThrows(IllegalStateException::class.java) { value.requireContext(wallet, UUID(9, 9), backup, funding, prior, probe) }
        assertThrows(IllegalStateException::class.java) { value.requireContext(wallet, operation, ByteArray(32), funding, prior, probe) }
        assertThrows(IllegalStateException::class.java) { value.requireContext(wallet, operation, backup, byteArrayOf(9), prior, probe) }
        assertThrows(IllegalStateException::class.java) { value.requireContext(wallet, operation, backup, funding, byteArrayOf(9), probe) }
        assertThrows(IllegalStateException::class.java) { value.requireContext(wallet, operation, backup, funding, prior, byteArrayOf(9)) }
        assertThrows(IllegalStateException::class.java) { value.requireIdentical(binding(byteArrayOf(9))) }
    }

    @Test fun fullSnapshotAndSignatureFitBeforeReservingAnySigningSlot() {
        val maximum = QuantumMigrationSigningBinding.MAX_FIELD_BYTES
        val value = QuantumMigrationSigningBinding.pack(wallet, operation, backup, ByteArray(maximum), byteArrayOf(1), byteArrayOf(1), byteArrayOf(1))
        assertEquals(QuantumMigrationSigningRecord.MAX_BINDING_BYTES, value.encode().size)
        val signed = QuantumMigrationSigningRecord.reserve(value.encode()).beginSigning().finishSigning(ByteArray(7856))
        assertEquals(QuantumMigrationSigningRecord.MAX_SIGNING_BYTES, signed.encode().size)
        assertThrows(IllegalArgumentException::class.java) {
            QuantumMigrationSigningBinding.pack(wallet, operation, backup, ByteArray(maximum + 1), byteArrayOf(1), byteArrayOf(1), byteArrayOf(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            QuantumMigrationSigningBinding.pack(wallet, operation, ByteArray(31), funding, prior, probe, request)
        }
    }

    @Test fun framingRejectsTruncationLengthsUnknownVersionAndTrailingData() {
        val bytes = binding().encode()
        for (size in bytes.indices) assertThrows(Exception::class.java) { QuantumMigrationSigningBinding.decode(bytes.copyOf(size)) }
        assertThrows(IllegalArgumentException::class.java) { QuantumMigrationSigningBinding.decode(bytes + byteArrayOf(0)) }
        for ((offset, number) in listOf(0 to 0, 68 to -1, 68 to Int.MAX_VALUE)) {
            val wrong = bytes.copyOf(); ByteBuffer.wrap(wrong).putInt(offset, number)
            assertThrows(Exception::class.java) { QuantumMigrationSigningBinding.decode(wrong) }
        }
        val zeroWallet = bytes.copyOf(); zeroWallet.fill(0, 4, 20)
        assertThrows(IllegalArgumentException::class.java) { QuantumMigrationSigningBinding.decode(zeroWallet) }
        val zeroOperation = bytes.copyOf(); zeroOperation.fill(0, 20, 36)
        assertThrows(IllegalArgumentException::class.java) { QuantumMigrationSigningBinding.decode(zeroOperation) }
    }
}
