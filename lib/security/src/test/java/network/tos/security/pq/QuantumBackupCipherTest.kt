package network.tos.security.pq

import org.junit.Assert.*
import org.junit.Test

class QuantumBackupCipherTest {
    @Test fun decryptsIndependentNodeCryptoVector() {
        // Public synthetic vector: Node crypto PBKDF2-SHA256(600000,32),
        // AES-256-GCM with the entire 41-byte header as AAD.
        val hex = "544f5351424b303101000102030405060708090a0b0c0d0e0f" +
            "101112131415161718191a1b00000010" +
            "6fd50eeb2c0badf12aef5f95b250a71f54d7da98c4df951ba88e41da3d23d2e4"
        val record = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertArrayEquals(ByteArray(16) { it.toByte() },
            QuantumBackupCipher.decrypt(record, "public vector password 😀".toCharArray()))
    }

    @Test fun roundtripUsesFreshSaltAndNonce() {
        val password = "public test password 😀".toCharArray()
        val payload = ByteArray(128) { it.toByte() }
        val first = QuantumBackupCipher.encrypt(payload, password)
        val second = QuantumBackupCipher.encrypt(payload, password)
        assertFalse(first.copyOfRange(9, 25).contentEquals(second.copyOfRange(9, 25)))
        assertFalse(first.copyOfRange(25, 37).contentEquals(second.copyOfRange(25, 37)))
        assertArrayEquals(payload, QuantumBackupCipher.decrypt(first, password))
        assertArrayEquals(payload, QuantumBackupCipher.decrypt(second, password))
    }

    @Test fun rejectsWrongPasswordAndAuthenticatedByteChanges() {
        val password = "public test password".toCharArray()
        val record = QuantumBackupCipher.encrypt(byteArrayOf(1, 2, 3), password)
        assertThrows(Exception::class.java) {
            QuantumBackupCipher.decrypt(record, "different test password".toCharArray())
        }
        for (offset in listOf(9, 25, 41, record.lastIndex)) {
            val corrupt = record.copyOf().also { it[offset] = (it[offset].toInt() xor 1).toByte() }
            assertThrows(javax.crypto.AEADBadTagException::class.java) {
                QuantumBackupCipher.decrypt(corrupt, password)
            }
        }
    }
}
