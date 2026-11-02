package network.tos.security.pq

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class QuantumBackupEnvelopeTest {
    private fun record(length: Int = 1): ByteArray =
        "TOSQBK01".toByteArray(Charsets.US_ASCII) + byteArrayOf(1) +
            ByteArray(28) + ByteBuffer.allocate(4).putInt(length).array() + ByteArray(length + 16)

    @Test fun acceptsExactBoundsAndOwnsInput() {
        for (length in listOf(1, 262144)) {
            val bytes = record(length)
            val parsed = QuantumBackupEnvelope.parse(bytes)
            bytes.fill(0)
            assertEquals(41, parsed.header.size)
            assertEquals('T'.code.toByte(), parsed.header[0])
            assertEquals(16, parsed.salt.size)
            assertEquals(12, parsed.nonce.size)
            assertEquals(length + 16, parsed.ciphertextAndTag.size)
        }
    }

    @Test fun rejectsTruncationTrailingBytesAndUntrustedLengths() {
        val valid = record()
        val invalid = mutableListOf(valid + byteArrayOf(0), record(262145))
        for (size in valid.indices) invalid += valid.copyOf(size)
        for (length in listOf(0, -1, Int.MIN_VALUE, 262145)) {
            invalid += valid.copyOf().also { ByteBuffer.wrap(it, 37, 4).putInt(length) }
        }
        for (bytes in invalid) assertThrows(IllegalArgumentException::class.java) {
            QuantumBackupEnvelope.parse(bytes)
        }
    }

    @Test fun rejectsEveryMagicByteAndUnknownProfile() {
        for (offset in 0..8) {
            val bytes = record().also { it[offset] = (it[offset].toInt() xor 1).toByte() }
            assertThrows(IllegalArgumentException::class.java) { QuantumBackupEnvelope.parse(bytes) }
        }
    }
}
