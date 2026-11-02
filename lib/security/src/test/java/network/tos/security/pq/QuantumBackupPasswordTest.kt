package network.tos.security.pq

import org.junit.Assert.*
import org.junit.Test

class QuantumBackupPasswordTest {
    @Test fun preservesWhitespaceAndUnicodeWithoutNormalization() {
        for (value in listOf("  password twelve  ", "é".repeat(12), "e\u0301".repeat(12), "😀".repeat(12))) {
            val encoded = QuantumBackupPassword.encode(value.toCharArray())
            assertArrayEquals(value.toByteArray(Charsets.UTF_8), encoded)
            encoded.fill(0)
        }
    }

    @Test fun rejectsMalformedUnicodeNulAndShortScalarCount() {
        for (value in listOf("a".repeat(11), "😀".repeat(6), "a".repeat(12) + '\u0000',
            "a".repeat(12) + '\uD800', "a".repeat(12) + '\uDC00')) {
            assertThrows(IllegalArgumentException::class.java) {
                QuantumBackupPassword.encode(value.toCharArray())
            }
        }
    }

    @Test fun enforcesUtf8ByteLimit() {
        assertEquals(1024, QuantumBackupPassword.encode("😀".repeat(256).toCharArray()).size)
        assertThrows(IllegalArgumentException::class.java) {
            QuantumBackupPassword.encode("😀".repeat(257).toCharArray())
        }
    }
}
