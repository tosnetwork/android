package network.tos.security.pq

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.MessageDigest
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/** Public test-only seeds; never use these for funds. */
@RunWith(AndroidJUnit4::class)
class QuantumBackupBindingDeviceTest {
    @Test fun nativeSeedsBindFullSnapshotKeysAndDigests() {
        val primarySeed = ByteArray(32) { 7 }
        val rescueSeed = ByteArray(48) { 8 }
        val primary = QuantumCrypto.publicKey(QuantumRole.PRIMARY, primarySeed)
        val rescue = QuantumCrypto.publicKey(QuantumRole.RESCUE, rescueSeed)
        fun role(id: Int, seed: ByteArray, key: ByteArray) = QuantumBackupPayload.Role(
            id, 0, 3, seed.copyOf(), MessageDigest.getInstance("SHA-256").digest(key),
            1, intArrayOf(), byteArrayOf(), byteArrayOf())
        val roles = listOf(role(1, primarySeed, primary), role(2, rescueSeed, rescue))
        fun payload(entries: List<QuantumBackupPayload.Role>): QuantumBackupPayload {
            val bytes = QuantumBackupPayload.encodeUnverified(-3, ByteArray(32), ByteArray(32),
                ByteArray(32), byteArrayOf(1), byteArrayOf(1), entries)
            return try { QuantumBackupPayload.parseUnverified(bytes) } finally { bytes.fill(0) }
        }
        try {
            payload(roles).use { parsed ->
                parsed.validateSnapshotKeys(primary, rescue)
                assertThrows(IllegalArgumentException::class.java) {
                    parsed.validateSnapshotKeys(primary.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }, rescue)
                }
                assertThrows(IllegalArgumentException::class.java) {
                    parsed.validateSnapshotKeys(primary, rescue.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })
                }
            }
            payload(listOf(roles[1])).use { it.validateSnapshotKeys(primary, rescue) }
            roles[0].seed[0] = (roles[0].seed[0].toInt() xor 1).toByte()
            payload(roles).use { parsed ->
                assertThrows(IllegalArgumentException::class.java) { parsed.validateSnapshotKeys(primary, rescue) }
            }
            roles[0].seed[0] = (roles[0].seed[0].toInt() xor 1).toByte()
            roles[1].publicKeyHash.fill(0)
            payload(roles).use { parsed ->
                assertThrows(IllegalArgumentException::class.java) { parsed.validateSnapshotKeys(primary, rescue) }
            }
        } finally {
            roles.forEach { it.wipe() }
            primarySeed.fill(0); rescueSeed.fill(0)
        }
    }
}
