package network.tos.security.pq

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuantumRegisteredCustodyDeviceTest {
    @Test fun registeredSigningUsesAuthenticatedNonzeroDerivationAndRejectsSubstitution() {
        val app = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = app.getSharedPreferences("registered-${UUID.randomUUID()}", 0)
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val network = ByteArray(32) { 3 }
        val context = QuantumSeedVault.Context(network, -239, 0xffffffffL, 17)
        val vault = QuantumSeedVault(prefs)
        try {
            for (role in QuantumRole.entries) {
                val publicKey = vault.importAndWipe("test", role, context, key, ByteArray(role.seedSize) { 9 })
                val digest = ByteArray(32) { 7 }
                val signature = vault.signRegistered("test", role, network, -239, key, publicKey, QuantumPurpose.POP, digest)
                assertTrue(QuantumCrypto.verify(role, QuantumPurpose.POP, publicKey, digest, signature))
                assertThrows(javax.crypto.AEADBadTagException::class.java) {
                    vault.publicKey("test", role, QuantumSeedVault.Context(network, -239, 0, 0), key)
                }
                assertThrows(javax.crypto.AEADBadTagException::class.java) {
                    vault.signRegistered("test", role, network, -238, key, publicKey, QuantumPurpose.POP, digest)
                }
                assertThrows(javax.crypto.AEADBadTagException::class.java) {
                    vault.signRegistered("test", role, ByteArray(32), -239, key, publicKey, QuantumPurpose.POP, digest)
                }
                assertThrows(IllegalArgumentException::class.java) {
                    vault.signRegistered("test", role, network, -239, key, ByteArray(publicKey.size), QuantumPurpose.POP, digest)
                }
                val name = "quantum.seed.v1.${role.id}.test"
                val record = Base64.decode(prefs.getString(name, null), Base64.NO_WRAP)
                assertEquals(2, record[0].toInt())
                record[record.lastIndex] = (record.last().toInt() xor 1).toByte()
                check(prefs.edit().putString(name, Base64.encodeToString(record, Base64.NO_WRAP)).commit())
                assertThrows(javax.crypto.AEADBadTagException::class.java) {
                    vault.signRegistered("test", role, network, -239, key, publicKey, QuantumPurpose.POP, digest)
                }
            }
        } finally { check(prefs.edit().clear().commit()) }
    }

    @Test fun originalV1RecordsRemainReadableAndRegisteredSigningNeverGuessesNonzeroContext() {
        val app = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = app.getSharedPreferences("legacy-registered-${UUID.randomUUID()}", 0)
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val network = ByteArray(32) { 3 }
        val vault = QuantumSeedVault(prefs)
        try {
            for (role in QuantumRole.entries) for (account in listOf(0L, 1L)) {
                val id = "test$account"
                val context = QuantumSeedVault.Context(network, -239, account, account)
                val name = "quantum.seed.v1.${role.id}.$id"
                val seed = ByteArray(role.seedSize) { 9 }
                try {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.ENCRYPT_MODE, key)
                    cipher.updateAAD("TOS-V5R2-SEED-v1".toByteArray(Charsets.US_ASCII) +
                        byteArrayOf(name.length.toByte()) + name.toByteArray(Charsets.US_ASCII) + context.encoding())
                    val record = byteArrayOf(1) + cipher.iv + cipher.doFinal(seed)
                    check(prefs.edit().putString(name, Base64.encodeToString(record, Base64.NO_WRAP)).commit())
                    val publicKey = QuantumCrypto.publicKey(role, seed)
                    assertArrayEquals(publicKey, vault.publicKey(id, role, context, key))
                    if (account == 0L) {
                        val digest = ByteArray(32) { 7 }
                        val signature = vault.signRegistered(id, role, network, -239, key, publicKey, QuantumPurpose.POP, digest)
                        assertTrue(QuantumCrypto.verify(role, QuantumPurpose.POP, publicKey, digest, signature))
                    } else assertThrows(javax.crypto.AEADBadTagException::class.java) {
                        vault.signRegistered(id, role, network, -239, key, publicKey, QuantumPurpose.POP, ByteArray(32))
                    }
                } finally { seed.fill(0) }
            }
        } finally { check(prefs.edit().clear().commit()) }
    }
}
