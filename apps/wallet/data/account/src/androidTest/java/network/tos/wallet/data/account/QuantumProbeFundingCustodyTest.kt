package network.tos.wallet.data.account

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import network.tos.blockchain.ton.contract.*
import network.tos.security.pq.*
import network.tos.wallet.data.account.pq.QuantumCreationCustody
import network.tos.wallet.data.account.pq.QuantumNetworkProfile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.ton.boc.BagOfCells
import java.math.BigInteger
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.KeyGenerator
import org.json.JSONObject

/** Public vectors only. Wrapper tests do not fabricate a journal handoff or App authentication. */
@RunWith(AndroidJUnit4::class)
class QuantumProbeFundingCustodyTest {
    @Test fun softwareWrappingReadsExistingKeyAfterCustodyReload() = verify(false)
    @Test fun androidKeystoreWrappingReadsExistingKeyAfterCustodyReload() = verify(true)
    private fun verify(deviceKey: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("funding-custody-${UUID.randomUUID()}", 0)
        val nextPrimary = ByteArray(32) { 19 }; val nextRescue = ByteArray(48) { 29 }
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val prefix = "quantum-backup-exchange-v1/ios-public-mixed-role-v1"
        val metadata = JSONObject(assets.open("$prefix.json").bufferedReader().use { it.readText() })
        check(metadata.getBoolean("is_public_test_material"))
        val encrypted = assets.open("$prefix.tosqbk").use { it.readBytes() }
        assertEquals(metadata.getString("cipher_sha256"), MessageDigest.getInstance("SHA-256")
            .digest(encrypted).joinToString("") { "%02x".format(it) })
        fun bytes(name: String) = metadata.getString(name).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val password = metadata.getString("password")
        try {
            val inspection = QuantumBackupInspection.inspect(encrypted, password.toCharArray(),
                metadata.getInt("global_id"), bytes("root_hash"), bytes("file_hash"), bytes("namespace"))
            val old = inspection.snapshot; val birth = inspection.birth; val id = UUID.randomUUID()
            val software = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val requests = mutableListOf<Boolean>()
            val wrapping: (Boolean) -> javax.crypto.SecretKey = { create ->
                requests.add(create)
                if (deviceKey) QuantumDeviceKey.get(create).also { assertNull(it.encoded) } else software
            }
            QuantumCreationCustody(prefs, wrapping).storeRecoveryMaterial(id, encrypted,
                password.toCharArray(), inspection)
            val custody = QuantumCreationCustody(prefs, wrapping)
            val profile = QuantumNetworkProfile(metadata.getInt("global_id"), metadata.getString("root_hash"), metadata.getString("file_hash"))
            val reloaded = custody.inspectRecoveryMaterial(id, birth, inspection.encryptedDigest, profile)
            val state = QuantumSelfPayState(old, walletId = birth.wallet.walletId, seqno = 7, retired = 2, epoch = 4u, rescueNonce = 9u)
            val successor = QuantumSelfPayAuthority(old.globalId, old.namespace, QuantumCrypto.publicKey(QuantumRole.PRIMARY, nextPrimary),
                QuantumCrypto.publicKey(QuantumRole.RESCUE, nextRescue), QuantumPolicy.REQUIRED)
            val challenge = ByteArray(32) { 11 }
            val probe = TosQuantumSelfPayProbe(birth.address, successor, challenge, 1120, 1000)
            val primary = QuantumCrypto.sign(QuantumRole.PRIMARY, QuantumPurpose.SELF_PAY_POP, nextPrimary, successor.primaryKey, probe.digest)
            val rescue = QuantumCrypto.sign(QuantumRole.RESCUE, QuantumPurpose.SELF_PAY_POP, nextRescue, successor.rescueKey, probe.digest)
            try {
                val amount = BigInteger("10000000")
                val request = TosQuantumSelfPayRequest(birth.address, state, QuantumAuthRole.RESCUE, 1100,
                    QuantumSelfPayAction.Execute(probe.fundingActions(probe.submission(primary, rescue), amount)), 1000)
                requests.clear()
                val signature = custody.signProbeFunding(id, reloaded, state, successor, challenge, 1120,
                    primary, rescue, amount, BagOfCells(request.request).toByteArray(), 1000)
                try {
                    assertEquals(listOf(false), requests)
                    assertTrue(QuantumCrypto.verify(QuantumRole.RESCUE, QuantumPurpose.SELF_PAY, old.rescueKey, request.digest, signature))
                    assertFalse(QuantumCrypto.verify(QuantumRole.RESCUE, QuantumPurpose.SELF_PAY_POP, old.rescueKey, request.digest, signature))
                } finally { signature.fill(0) }
            } finally { primary.fill(0); rescue.fill(0) }
        } finally {
            encrypted.fill(0); nextPrimary.fill(0); nextRescue.fill(0)
            check(prefs.edit().clear().commit())
        }
    }
}
