package network.tos.security.pq

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.MessageDigest
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import network.tos.blockchain.TosQuantumMnemonic
import org.ton.mnemonic.Mnemonic
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import java.util.UUID
import javax.crypto.KeyGenerator
import org.junit.Assert.assertArrayEquals

/** Public test-only seeds; never use these for funds. */
@RunWith(AndroidJUnit4::class)
class QuantumBackupBindingDeviceTest {
    @Test fun exportReadsProtectedCustodyInsteadOfTemplateSeedAndConsumesPassword() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val prefs = app.getSharedPreferences("quantum-backup-${UUID.randomUUID()}", 0)
        val vault = QuantumSeedVault(prefs)
        val wrapping = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val network = ByteArray(32) { (it + 32).toByte() }
        val master = ByteArray(32) { it.toByte() }
        val seed = QuantumKdf.deriveAndWipe(QuantumKdf.Material.PRIMARY, master.copyOf(), network, -239, 1, 3)
        val primary = QuantumCrypto.publicKey(QuantumRole.PRIMARY, seed)
        val rescue = QuantumCrypto.publicKey(QuantumRole.RESCUE, ByteArray(48) { 8 })
        val context = QuantumSeedVault.Context(network, -239, 1, 3)
        val entry = QuantumBackupPayload.Role(1, 1, 3, ByteArray(32),
            MessageDigest.getInstance("SHA-256").digest(primary), 3, intArrayOf(), byteArrayOf(), master.copyOf())
        val bytes = QuantumBackupPayload.encodeUnverified(-239, ByteArray(32), ByteArray(32), network,
            byteArrayOf(1), byteArrayOf(1), listOf(entry))
        try {
            vault.importAndWipe("test", QuantumRole.PRIMARY, context, wrapping, seed.copyOf())
            QuantumBackupPayload.parseUnverified(bytes).use { template ->
                val password = "public export password".toCharArray()
                val encrypted = vault.encryptedBackup("test", wrapping, template, primary, rescue, password)
                assertArrayEquals(CharArray(password.size), password)
                val plaintext = QuantumBackupCipher.decrypt(encrypted, "public export password".toCharArray())
                try {
                    QuantumBackupPayload.parseUnverified(plaintext).use { restored ->
                        assertArrayEquals(seed, restored.roles.single().seed)
                        restored.validateSnapshotKeys(primary, rescue)
                        restored.validateProvenance()
                    }
                } finally { plaintext.fill(0) }
                val deniedPassword = "public export password".toCharArray()
                assertThrows(Exception::class.java) {
                    vault.encryptedBackup("missing", wrapping, template, primary, rescue, deniedPassword)
                }
                assertArrayEquals(CharArray(deniedPassword.size), deniedPassword)
                val mismatchPassword = "public export password".toCharArray()
                assertThrows(IllegalArgumentException::class.java) {
                    vault.encryptedBackup("test", wrapping, template, ByteArray(primary.size), rescue, mismatchPassword)
                }
                assertArrayEquals(CharArray(mismatchPassword.size), mismatchPassword)
            }
        } finally {
            bytes.fill(0); entry.wipe(); seed.fill(0); master.fill(0)
            check(prefs.edit().clear().commit())
        }
    }

    @Test fun provenanceBindsOriginalMaterialsToExactDerivationContext() {
        val network = ByteArray(32) { (it + 32).toByte() }
        val words = List(11) { "abandon" } + "amateur"
        val dictionary = Mnemonic.mnemonicWords()
        val indices = words.map { dictionary.indexOf(it) }.toIntArray()
        for (source in 2..3) for (role in QuantumRole.entries) {
            val master = if (source == 2) TosQuantumMnemonic.masterAndWipePassword(words, charArrayOf())
                else ByteArray(32) { it.toByte() }
            val seed = QuantumKdf.deriveAndWipe(if (role == QuantumRole.PRIMARY)
                QuantumKdf.Material.PRIMARY else QuantumKdf.Material.RESCUE,
                master.copyOf(), network, -239, 1, 3)
            fun check(networkInput: ByteArray = network, global: Int = -239,
                      account: Long = 1, generation: Long = 3, password: ByteArray = byteArrayOf(),
                      seedInput: ByteArray = seed) {
                val entry = QuantumBackupPayload.Role(role.id, account, generation, seedInput.copyOf(),
                    ByteArray(32), source, if (source == 2) indices.copyOf() else intArrayOf(),
                    password.copyOf(), if (source == 3) master.copyOf() else byteArrayOf())
                try {
                    val bytes = QuantumBackupPayload.encodeUnverified(global, ByteArray(32), ByteArray(32),
                        networkInput, byteArrayOf(1), byteArrayOf(1), listOf(entry))
                    try { QuantumBackupPayload.parseUnverified(bytes).use { it.validateProvenance() } }
                    finally { bytes.fill(0) }
                } finally { entry.wipe() }
            }
            try {
                check()
                assertThrows(IllegalArgumentException::class.java) { check(networkInput = ByteArray(32)) }
                assertThrows(IllegalArgumentException::class.java) { check(global = -238) }
                assertThrows(IllegalArgumentException::class.java) { check(account = 2) }
                assertThrows(IllegalArgumentException::class.java) { check(generation = 4) }
                assertThrows(IllegalArgumentException::class.java) {
                    check(seedInput = seed.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })
                }
                if (source == 2) assertThrows(IllegalArgumentException::class.java) {
                    check(password = "different password".toByteArray())
                }
            } finally { master.fill(0); seed.fill(0) }
        }
    }

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
