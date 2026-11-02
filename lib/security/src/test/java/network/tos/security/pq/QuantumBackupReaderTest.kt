package network.tos.security.pq

import org.junit.Assert.*
import org.junit.Test

class QuantumBackupReaderTest {
    @Test fun readsBigEndianUnsignedValuesWithoutNarrowing() {
        val reader = QuantumBackupReader(byteArrayOf(-1, -1, -1, -1, -1, -1, -1))
        assertEquals(255, reader.unsignedByte())
        assertEquals(65535, reader.unsignedShort())
        assertEquals(4294967295L, reader.unsignedInt())
        reader.requireEnd()
    }

    @Test fun rejectsEveryTruncationAndUnsignedOversize() {
        val valid = byteArrayOf(0, 0, 0, 2, 7, 8)
        for (size in 1 until valid.size) {
            assertThrows(IllegalArgumentException::class.java) {
                QuantumBackupReader(valid.copyOf(size)).boundedBlob(2)
            }
        }
        for (length in listOf(byteArrayOf(-1, -1, -1, -1), byteArrayOf(0, 0, 0, 0), byteArrayOf(0, 0, 0, 3))) {
            assertThrows(IllegalArgumentException::class.java) { QuantumBackupReader(length).boundedBlob(2) }
        }
        assertArrayEquals(byteArrayOf(7, 8), QuantumBackupReader(valid).boundedBlob(2))
    }

    @Test fun rejectsTrailingAndNegativeReads() {
        val reader = QuantumBackupReader(byteArrayOf(1))
        assertThrows(IllegalArgumentException::class.java) { reader.requireEnd() }
        assertThrows(IllegalArgumentException::class.java) { reader.fixed(-1) }
        assertEquals(1, reader.unsignedByte())
        reader.requireEnd()
        assertThrows(IllegalArgumentException::class.java) { reader.unsignedByte() }
    }
}
