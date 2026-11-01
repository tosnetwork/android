package network.tos.security.pq

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.KeyGenerator
import network.tos.blockchain.TosQuantumMnemonic
import network.tos.blockchain.ton.contract.*
import network.tos.blockchain.ton.extensions.toByteArray
import org.ton.mnemonic.Mnemonic
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Public frozen test secrets/passwords only. Never use any generated address for funds. */
@RunWith(AndroidJUnit4::class)
class QuantumBackupExchangeDeviceTest {
    @Test fun importsActualIosMixedRoleFile() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val prefix = "quantum-backup-exchange-v1/ios-public-mixed-role-v1"
        val bytes = app.assets.open("$prefix.tosqbk").use { it.readBytes() }
        val expected = JSONObject(app.assets.open("$prefix.json").use { it.readBytes().toString(Charsets.UTF_8) })
        fun hex(value: ByteArray) = value.joinToString("") { "%02x".format(it.toInt() and 255) }
        fun sha(value: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(value))
        assertTrue(expected.getBoolean("is_public_test_material"))
        assertEquals("ios", expected.getString("producer"))
        assertEquals(expected.getString("cipher_sha256"), sha(bytes))
        val password = "public exchange password 😀".toCharArray()
        // Trust values are fixed by the fixture specification, not selected by the incoming file.
        val inspected = QuantumBackupInspection.inspect(bytes, password, -239,
            ByteArray(32) { 4 }, ByteArray(32) { 5 }, ByteArray(32) { 3 })
        assertArrayEquals(CharArray(password.size), password)
        assertEquals(expected.getString("birth_address"), "0:" + hex(inspected.birth.address.address.toByteArray()))
        assertEquals(expected.getString("authority_hash"), hex(inspected.snapshot.hash))
        assertEquals(expected.getString("primary_key_hash"), sha(inspected.snapshot.primaryKey))
        assertEquals(expected.getString("rescue_key_hash"), sha(inspected.snapshot.rescueKey))
        assertEquals(listOf(QuantumRole.PRIMARY, QuantumRole.RESCUE), inspected.roles.map { it.role })
        assertEquals(listOf(1L, 2L), inspected.roles.map { it.account })
        assertEquals(listOf(19L, 20L), inspected.roles.map { it.generation })
        assertEquals(listOf(2, 3), inspected.roles.map { it.sourceProfile })
        val plaintext = QuantumBackupCipher.decrypt(bytes, "public exchange password 😀".toCharArray())
        try {
            QuantumBackupPayload.parseUnverified(plaintext).use { payload ->
                val words = List(23) { "abandon" } + "chicken"
                val dictionary = Mnemonic.mnemonicWords()
                assertArrayEquals(words.map { dictionary.indexOf(it) }.toIntArray(), payload.roles[0].wordIndices)
                assertArrayEquals(" public test password ".toByteArray(), payload.roles[0].phrasePassword)
                assertArrayEquals(ByteArray(32) { it.toByte() }, payload.roles[1].master)
            }
        } finally { plaintext.fill(0) }
        assertThrows(Exception::class.java) {
            QuantumBackupInspection.inspect(bytes, "incorrect public exchange password".toCharArray(), -239,
                ByteArray(32) { 4 }, ByteArray(32) { 5 }, ByteArray(32) { 3 })
        }
        assertThrows(IllegalArgumentException::class.java) {
            QuantumBackupInspection.inspect(bytes, "public exchange password 😀".toCharArray(), -238,
                ByteArray(32) { 4 }, ByteArray(32) { 5 }, ByteArray(32) { 3 })
        }
        val tampered = bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) {
            QuantumBackupInspection.inspect(tampered, "public exchange password 😀".toCharArray(), -239,
                ByteArray(32) { 4 }, ByteArray(32) { 5 }, ByteArray(32) { 3 })
        }
    }

    @Test fun produceMixedRoleExchangeFile() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val prefs = app.getSharedPreferences("exchange-${UUID.randomUUID()}", 0)
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val network = ByteArray(32) { (it + 32).toByte() }
        val root = ByteArray(32) { 3 }; val file = ByteArray(32) { 4 }
        val words = List(23) { "abandon" } + "chicken"
        val dictionary = Mnemonic.mnemonicWords()
        val primaryMaster = TosQuantumMnemonic.masterAndWipePassword(words, " public test password ".toCharArray())
        val rescueMaster = ByteArray(32) { it.toByte() }
        val primarySeed = QuantumKdf.deriveAndWipe(QuantumKdf.Material.PRIMARY, primaryMaster, network, -239, 1, 19)
        val rescueSeed = QuantumKdf.deriveAndWipe(QuantumKdf.Material.RESCUE, rescueMaster.copyOf(), network, -239, 2, 20)
        val primary = QuantumCrypto.publicKey(QuantumRole.PRIMARY, primarySeed)
        val rescue = QuantumCrypto.publicKey(QuantumRole.RESCUE, rescueSeed)
        val authority = QuantumSelfPayAuthority(-239, network, primary, rescue)
        val birth = TosQuantumSelfPayIdentity(TosQuantumSelfPayWallet(authority))
        fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        val roles = listOf(QuantumBackupPayload.Role(1, 1, 19, primarySeed.copyOf(), sha(primary), 2,
            words.map { dictionary.indexOf(it) }.toIntArray(), " public test password ".toByteArray(), byteArrayOf()),
            QuantumBackupPayload.Role(2, 2, 20, rescueSeed.copyOf(), sha(rescue), 3,
                intArrayOf(), byteArrayOf(), rescueMaster.copyOf()))
        val plaintext = QuantumBackupPayload.encodeUnverified(-239, root, file, network, birth.toBoc(), authority.cell.toByteArray(), roles)
        try {
            val initial = QuantumBackupCipher.encrypt(plaintext, "old public exchange password".toCharArray())
            val inspection = QuantumBackupInspection.inspect(initial, "old public exchange password".toCharArray(), -239, root, file, network)
            val vault = QuantumRecoveryMaterialVault(prefs)
            val id = UUID.randomUUID()
            vault.storeValidated(initial, "old public exchange password".toCharArray(), inspection, id, key)
            val retained = vault.inspectStored(id, birth, inspection.encryptedDigest, key, -239, root, file, network)
            val exported = vault.exportEncrypted(id, retained, key, authority, listOf(QuantumRole.PRIMARY, QuantumRole.RESCUE),
                "public exchange password 😀".toCharArray())
            val metadata = JSONObject().put("producer", "android").put("is_public_test_material", true)
                .put("password", "public exchange password 😀").put("global_id", -239)
                .put("root_hash", hex(root)).put("file_hash", hex(file)).put("namespace", hex(network))
                .put("birth_address", "0:" + hex(birth.address.address.toByteArray())).put("authority_hash", hex(authority.hash))
                .put("cipher_sha256", hex(sha(exported))).put("primary_key_hash", hex(sha(primary))).put("rescue_key_hash", hex(sha(rescue)))
            File(app.cacheDir, "android-public-mixed-role-v1.tosqbk").writeBytes(exported)
            File(app.cacheDir, "android-public-mixed-role-v1.json").writeText(metadata.toString(2))
        } finally {
            plaintext.fill(0); primarySeed.fill(0); rescueSeed.fill(0); rescueMaster.fill(0); roles.forEach { it.wipe() }
            check(prefs.edit().clear().commit())
        }
    }
}
