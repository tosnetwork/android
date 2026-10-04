package network.tos.wallet

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import network.tos.blockchain.ton.contract.TosPqWallet
import network.tos.blockchain.ton.extensions.base64
import org.ton.block.AddrStd
import org.json.JSONObject
import network.tos.security.pq.PqAlgorithm
import network.tos.security.pq.PqSeedVault
import network.tos.security.pq.PqVerifier
import network.tos.security.pq.PqBackup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import javax.crypto.spec.SecretKeySpec
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PqWalletCryptoTest {
    @Test fun mldsaVaultSignsAndRejectsTampering() = checkProfile(PqAlgorithm.MLDSA44)
    @Test fun falconVaultSignsAndRejectsTampering() = checkProfile(PqAlgorithm.FALCON512_PADDED)
    private fun checkProfile(algorithm: PqAlgorithm) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("pq-test-${UUID.randomUUID()}", 0)
        val master = SecretKeySpec(ByteArray(32) { 4 }, "AES") // PUBLIC TEST DATA
        val seed = ByteArray(32) { 0xa0.toByte() } // PUBLIC TEST DATA
        val vault = PqSeedVault(prefs)
        try {
            val publicKey = vault.restore("test", algorithm, master, seed)
            assertEquals(algorithm.publicKeySize, publicKey.size)
            assertArrayEquals(publicKey, PqSeedVault(prefs).publicKey("test", algorithm, master))
            val testContext = InstrumentationRegistry.getInstrumentation().context
            val vectors = JSONObject(testContext.assets.open("tos-pq-backup-vectors.json").bufferedReader().use { it.readText() }).getJSONArray("vectors")
            val vector = vectors.getJSONObject(algorithm.id - 1)
            val password = vector.getString("password").toCharArray()
            val known = vector.getString("record").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            assertArrayEquals(seed, PqBackup.decrypt(known, algorithm, 3, password))
            val backup = vault.backup("test", algorithm, 3, master, password)
            assertEquals(121, backup.size)
            assertArrayEquals(publicKey, vault.restoreBackup("restored", algorithm, 3, master, backup, password))
            assertArrayEquals(publicKey, vault.publicKey("restored", algorithm, master))
            assertThrows(Exception::class.java) { vault.restoreBackup("restored", algorithm, 3, master, backup, password) }
            assertThrows(Exception::class.java) { PqBackup.decrypt(known, algorithm, 4, password) }
            val other = if (algorithm == PqAlgorithm.MLDSA44) PqAlgorithm.FALCON512_PADDED else PqAlgorithm.MLDSA44
            assertThrows(Exception::class.java) { PqBackup.decrypt(known, other, 3, password) }
            assertThrows(Exception::class.java) { PqBackup.decrypt(known, algorithm, 3, (vector.getString("password") + "bad").toCharArray()) }
            assertThrows(Exception::class.java) { PqBackup.decrypt(known.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }, algorithm, 3, password) }
            assertThrows(Exception::class.java) { PqBackup.decrypt(known.copyOf().apply { this[13] = (this[13].toInt() xor 1).toByte() }, algorithm, 3, password) }
            password.fill('\u0000')
            vault.delete("restored", algorithm)
            val message = if (algorithm == PqAlgorithm.MLDSA44) ByteArray(32) { 1 } else
                "TOS-AUTH-FALCON512-PADDED-v1".toByteArray() + ByteArray(5) + ByteArray(32) { 2 } + ByteArray(32) { 1 }
            val signature = vault.sign("test", algorithm, master, publicKey, message)
            assertTrue(PqVerifier.verify(algorithm, publicKey, message, signature))
            assertFalse(signature.contentEquals(vault.sign("test", algorithm, master, publicKey, message)))
            val bad = message.copyOf().apply { this[lastIndex] = 3 }
            assertFalse(PqVerifier.verify(algorithm, publicKey, bad, signature))
            assertFalse(PqVerifier.verify(algorithm, publicKey, message, signature.copyOf(signature.size-1)))
            assertThrows(IllegalArgumentException::class.java) {
                vault.sign("test", algorithm, master, ByteArray(publicKey.size), message)
            }
            assertThrows(Exception::class.java) {
                vault.sign("test", algorithm, SecretKeySpec(ByteArray(32) { 5 }, "AES"), publicKey, message)
            }
            assertThrows(IllegalArgumentException::class.java) { vault.restore("test", algorithm, master, seed) }
            val wallet = TosPqWallet(algorithm.id, publicKey, 3)
            val payload = wallet.transferPayload(AddrStd.parse("0:${"22".repeat(32)}"), 10000000L, "PQ TOS 测试 🌌")
            val args = InstrumentationRegistry.getArguments()
            val now = args.getString("pq_chain_time")?.toLong() ?: 1780000000L
            val nonceText = (args.getString("pq_nonce") ?: "0").split(",")
            val nonce = nonceText[if (nonceText.size == 2) algorithm.id - 1 else 0].toULong()
            val request = wallet.request(0u, nonce, now + 600, now, payload)
            val pqSignature = vault.sign("test", algorithm, master, publicKey, wallet.signingMessage(request))
            fun raw(a: AddrStd) = "${a.workchainId}:${a.address.toByteArray().joinToString("") { "%02x".format(it) }}"
            val wire = JSONObject().put("platform", "android").put("algorithm", algorithm.id).put("chain_time", now).put("nonce", nonce.toString())
                .put("public_key", publicKey.joinToString("") { "%02x".format(it) })
                .put("module_address", raw(wallet.moduleAddress)).put("wallet_address", raw(wallet.address))
                .put("module_code", wallet.moduleCode.base64()).put("module_data", wallet.moduleData.base64())
                .put("wallet_code", wallet.walletCode.base64()).put("wallet_data", wallet.walletData.base64())
                .put("request", request.base64()).put("submission", wallet.submission(request, pqSignature).base64())
            java.io.File(context.filesDir, "pq-public-test-wire-${algorithm.id}.json").writeText(wire.toString())
            val record = prefs.getString("pq.v1.${algorithm.id}.test", null)!!
            val decoded = android.util.Base64.decode(record, android.util.Base64.NO_WRAP)
            decoded[decoded.lastIndex] = (decoded.last().toInt() xor 1).toByte()
            prefs.edit().putString("pq.v1.${algorithm.id}.test", android.util.Base64.encodeToString(decoded, android.util.Base64.NO_WRAP)).commit()
            assertThrows(Exception::class.java) { vault.publicKey("test", algorithm, master) }
            vault.delete("test", algorithm)
            assertThrows(Exception::class.java) { vault.publicKey("test", algorithm, master) }
        } finally { seed.fill(0); prefs.edit().clear().commit() }
    }
}
