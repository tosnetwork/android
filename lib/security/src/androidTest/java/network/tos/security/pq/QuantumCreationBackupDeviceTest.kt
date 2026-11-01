package network.tos.security.pq

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import network.tos.blockchain.TosQuantumMnemonic
import network.tos.blockchain.ton.contract.*
import org.ton.mnemonic.Mnemonic
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Fixed public native-TOS phrase only; never use these derived addresses for funds. */
@RunWith(AndroidJUnit4::class)
class QuantumCreationBackupDeviceTest {
    private val words = "twice half glass steak version only friend bone addict during joke offer fog punch panel true regular letter topple often afraid hint ready exhibit".split(" ")
    private val namespace = ByteArray(32) { (it + 32).toByte() }
    private val root = ByteArray(32) { 3 }
    private val file = ByteArray(32) { 4 }
    private fun fixture(generation: Long = 0, includeRescue: Boolean = true,
                        body: (QuantumSeedVault, SecretKey, String, TosQuantumSelfPayIdentity, android.content.SharedPreferences) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val prefs = app.getSharedPreferences("creation-export-${UUID.randomUUID()}", 0)
        val id = UUID.randomUUID().toString()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val master = TosQuantumMnemonic.masterAndWipePassword(words, charArrayOf())
        val primary = QuantumKdf.deriveAndWipe(QuantumKdf.Material.PRIMARY, master.copyOf(), namespace, 3, 0, generation)
        val rescue = QuantumKdf.deriveAndWipe(QuantumKdf.Material.RESCUE, master.copyOf(), namespace, 3, 0, generation)
        try {
            val authority = QuantumSelfPayAuthority(3, namespace,
                QuantumCrypto.publicKey(QuantumRole.PRIMARY, primary), QuantumCrypto.publicKey(QuantumRole.RESCUE, rescue))
            val birth = TosQuantumSelfPayIdentity(TosQuantumSelfPayWallet(authority))
            val vault = QuantumSeedVault(prefs)
            val context = QuantumSeedVault.Context(namespace, 3, 0, generation)
            vault.importAndWipe(id, QuantumRole.PRIMARY, context, key, primary)
            if (includeRescue) vault.importAndWipe(id, QuantumRole.RESCUE, context, key, rescue)
            body(vault, key, id, birth, prefs)
        } finally { master.fill(0); primary.fill(0); rescue.fill(0); check(prefs.edit().clear().commit()) }
    }

    @Test fun ciphertextRetainsBirthNativeWordsAndCurrentPolicy() = fixture { vault, key, id, birth, prefs ->
        val before = prefs.all.toMap()
        val original = birth.wallet.initialAuthority
        val current = QuantumSelfPayAuthority(3, namespace, original.primaryKey, original.rescueKey, QuantumPolicy.REQUIRED)
        val password = "public creation export password".toCharArray()
        val encrypted = vault.encryptedCreationBackup(id, key, birth, current, root, file, words, password)
        assertTrue(password.all { it == '\u0000' })
        val inspection = QuantumBackupInspection.inspect(encrypted, "public creation export password".toCharArray(), 3, root, file, namespace)
        assertEquals(birth, inspection.birth)
        assertArrayEquals(current.hash, inspection.snapshot.hash)
        val plaintext = QuantumBackupCipher.decrypt(encrypted, "public creation export password".toCharArray())
        try {
            QuantumBackupPayload.parseUnverified(plaintext).use { payload ->
                payload.validateProvenance()
                assertArrayEquals(birth.toBoc(), payload.birthBoc)
                assertEquals(listOf(1, 2), payload.roles.map { it.role })
                assertTrue(payload.roles.all { it.account == 0L && it.generation == 0L && it.sourceProfile == 2 && it.phrasePassword.isEmpty() })
                val expected = words.map { Mnemonic.mnemonicWords().indexOf(it) }.toIntArray()
                payload.roles.forEach { assertArrayEquals(expected, it.wordIndices) }
            }
        } finally { plaintext.fill(0); encrypted.fill(0) }
        assertEquals(before, prefs.all)
    }

    @Test fun wrongProvenanceOrAuthorityIsRejectedWithoutCustodyWrites() = fixture { vault, key, id, birth, prefs ->
        val before = prefs.all.toMap()
        for (wrongWords in listOf(false, true)) {
            val password = "public rejected export password".toCharArray()
            val original = birth.wallet.initialAuthority
            val authority = if (wrongWords) original else QuantumSelfPayAuthority(3, namespace, ByteArray(1312) { 7 }, original.rescueKey)
            assertThrows(Exception::class.java) {
                vault.encryptedCreationBackup(id, key, birth, authority, root, file,
                    if (wrongWords) List(24) { "abandon" } else words, password)
            }
            assertTrue(password.all { it == '\u0000' }); assertEquals(before, prefs.all)
        }
    }

    @Test fun incompleteOrNonzeroGenerationCustodyCannotMasqueradeAsCreation() {
        for ((generation, rescue) in listOf(0L to false, 1L to true)) fixture(generation, rescue) { vault, key, id, birth, prefs ->
            val before = prefs.all.toMap(); val password = "public incomplete export password".toCharArray()
            assertThrows(Exception::class.java) {
                vault.encryptedCreationBackup(id, key, birth, birth.wallet.initialAuthority, root, file, words, password)
            }
            assertTrue(password.all { it == '\u0000' }); assertEquals(before, prefs.all)
        }
    }

    @Test fun weakPinAndShortUnicodePasswordAreConsumedWithoutExport() = fixture { vault, key, id, birth, prefs ->
        val before = prefs.all.toMap()
        for (text in listOf("1234", "😀".repeat(6))) {
            val password = text.toCharArray()
            assertThrows(IllegalArgumentException::class.java) {
                vault.encryptedCreationBackup(id, key, birth, birth.wallet.initialAuthority, root, file, words, password)
            }
            assertTrue(password.all { it == '\u0000' }); assertEquals(before, prefs.all)
        }
    }
}
