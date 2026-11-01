package network.tos.wallet.data.account

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import network.tos.blockchain.ton.contract.*
import network.tos.security.pq.QuantumSuccessorMaterialVault
import network.tos.security.pq.QuantumCrypto
import network.tos.security.pq.QuantumRole
import network.tos.security.pq.QuantumPurpose
import network.tos.security.pq.QuantumBackupInspection
import network.tos.security.pq.QuantumRecoveryMaterialVault
import network.tos.wallet.data.account.pq.QuantumCreationCustody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import javax.crypto.KeyGenerator

/** Public local identities and software AES only, not Keystore or live migration acceptance. */
@RunWith(AndroidJUnit4::class)
class QuantumSuccessorMaterialTest {
    @Test fun encryptedSuccessorBackupSurvivesLossAndRejectsSubstitution() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "successor-export-${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val old = QuantumSelfPayAuthority(3, ByteArray(32) { 43 }, ByteArray(1312) { 1 }, ByteArray(32) { 2 })
        val birth = TosQuantumSelfPayIdentity(TosQuantumSelfPayWallet(old))
        val state = QuantumSelfPayState(old, retired = 2, epoch = 1u)
        val id = UUID.randomUUID(); val root = ByteArray(32) { 1 }; val file = ByteArray(32) { 2 }
        val vault = QuantumSuccessorMaterialVault(prefs)
        val secret = "successor-backup-test-password".toCharArray()
        var encrypted = ByteArray(0)
        try {
            val stage = vault.generate(id, birth, state, root, file, key)
            val original = prefs.all.toMap()
            encrypted = vault.exportEncrypted(id, birth, state, root, file, key,
                stage.inspection.encryptedDigest, stage.inspection.snapshot, secret)
            assertTrue(secret.all { it == '\u0000' })
            assertEquals(original, prefs.all)
            for (wrongDigest in listOf(ByteArray(31), ByteArray(32) { 9 })) {
                val password = "successor-backup-test-password".toCharArray()
                assertThrows(IllegalArgumentException::class.java) {
                    vault.exportEncrypted(id, birth, state, root, file, key, wrongDigest, stage.inspection.snapshot, password)
                }
                assertTrue(password.all { it == '\u0000' })
            }
            val password = "successor-backup-test-password".toCharArray()
            val other = QuantumSelfPayAuthority(old.globalId, old.namespace, old.primaryKey, old.rescueKey, QuantumPolicy.REQUIRED)
            assertThrows(IllegalArgumentException::class.java) {
                vault.exportEncrypted(id, birth, state, root, file, key, stage.inspection.encryptedDigest, other, password)
            }
            assertTrue(password.all { it == '\u0000' })
            assertEquals(original, prefs.all)
            // Simulate complete loss of the original local material and device wrapping key.
            assertTrue(prefs.edit().clear().commit())
            val restored = QuantumBackupInspection.inspect(encrypted, "successor-backup-test-password".toCharArray(),
                old.globalId, root, file, old.namespace)
            assertEquals(birth, restored.birth)
            assertArrayEquals(stage.inspection.snapshot.hash, restored.snapshot.hash)
            assertEquals(2, restored.roles.size)
            assertTrue(restored.roles.all { it.sourceProfile == 3 })
            val newKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val newId = UUID.randomUUID()
            QuantumRecoveryMaterialVault(prefs).storeValidated(encrypted, "successor-backup-test-password".toCharArray(), restored, newId, newKey)
            val reloaded = QuantumRecoveryMaterialVault(prefs).inspectStored(newId, birth, restored.encryptedDigest,
                newKey, old.globalId, root, file, old.namespace)
            assertArrayEquals(restored.snapshot.hash, reloaded.snapshot.hash)
            assertThrows(java.security.GeneralSecurityException::class.java) {
                QuantumBackupInspection.inspect(encrypted, "wrong-password-valid".toCharArray(), old.globalId, root, file, old.namespace)
            }
        } finally { encrypted.fill(0); secret.fill('\u0000'); context.deleteSharedPreferences(name) }
    }
    @Test fun independentStagingReloadBindingTamperAndDeletion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "successor-test-${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val old = QuantumSelfPayAuthority(3, ByteArray(32) { 43 }, ByteArray(1312) { 1 }, ByteArray(32) { 2 })
        val birth = TosQuantumSelfPayIdentity(TosQuantumSelfPayWallet(old))
        val current = QuantumSelfPayState(old, retired = 2, epoch = 1u)
        val id = UUID.randomUUID(); val root = ByteArray(32) { 1 }; val file = ByteArray(32) { 2 }
        val vault = QuantumSuccessorMaterialVault(prefs)
        try {
            val staged = vault.generate(id, birth, current, root, file, key)
            val authority = staged.inspection.snapshot
            assertFalse(old.primaryKey.contentEquals(authority.primaryKey))
            assertFalse(old.rescueKey.contentEquals(authority.rescueKey))
            assertEquals(QuantumPolicy.REQUIRED, authority.policy)
            assertEquals(2, staged.inspection.roles.size)
            assertTrue(staged.inspection.roles.all { it.sourceProfile == 3 })
            val reload = vault.inspect(id, birth, current, root, file, key)
            assertArrayEquals(authority.hash, reload.inspection.snapshot.hash)
            assertArrayEquals(staged.inspection.encryptedDigest, reload.inspection.encryptedDigest)
            val challenge = ByteArray(32) { 7 }
            val probe = TosQuantumSelfPayProbe(birth.address, authority, challenge, 1120, 1000)
            val signatures = vault.signProbe(id, birth, current, root, file, key, staged.inspection.encryptedDigest,
                authority, challenge, 1120, 1000)
            try {
                assertTrue(QuantumCrypto.verify(QuantumRole.PRIMARY, QuantumPurpose.SELF_PAY_POP, authority.primaryKey, probe.digest, signatures.first))
                assertTrue(QuantumCrypto.verify(QuantumRole.RESCUE, QuantumPurpose.SELF_PAY_POP, authority.rescueKey, probe.digest, signatures.second))
                assertFalse(QuantumCrypto.verify(QuantumRole.RESCUE, QuantumPurpose.POP, authority.rescueKey, probe.digest, signatures.second))
                val other = TosQuantumSelfPayProbe(birth.address, authority, ByteArray(32) { 8 }, 1120, 1000)
                assertFalse(QuantumCrypto.verify(QuantumRole.PRIMARY, QuantumPurpose.SELF_PAY_POP, authority.primaryKey, other.digest, signatures.first))
            } finally { signatures.first.fill(0); signatures.second.fill(0) }
            assertThrows(IllegalArgumentException::class.java) {
                vault.signProbe(id, birth, current, root, file, key, ByteArray(32) { 8 }, authority, challenge, 1120, 1000)
            }
            assertThrows(IllegalArgumentException::class.java) {
                vault.signProbe(id, birth, current, root, file, key, staged.inspection.encryptedDigest, old, challenge, 1120, 1000)
            }
            assertThrows(IllegalArgumentException::class.java) {
                vault.signProbe(id, birth, current, root, file, key, staged.inspection.encryptedDigest, authority, challenge, 1120, 1120)
            }
            assertThrows(IllegalStateException::class.java) { vault.generate(id, birth, current, root, file, key) }
            assertThrows(IllegalArgumentException::class.java) {
                vault.inspect(id, birth, QuantumSelfPayState(old, retired = 2, epoch = 2u), root, file, key)
            }
            val entry = "quantum.successor.material.v1.$id"
            val original = prefs.getString(entry, null)!!
            val other = UUID.randomUUID()
            assertTrue(prefs.edit().putString("quantum.successor.material.v1.$other", original).commit())
            assertThrows(java.security.GeneralSecurityException::class.java) { vault.inspect(other, birth, current, root, file, key) }
            val corrupt = Base64.decode(original, Base64.NO_WRAP).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            assertTrue(prefs.edit().putString(entry, Base64.encodeToString(corrupt, Base64.NO_WRAP)).commit())
            assertThrows(java.security.GeneralSecurityException::class.java) { vault.inspect(id, birth, current, root, file, key) }
            assertTrue(prefs.edit().putString(entry, original).commit())
            val custody = QuantumCreationCustody(prefs) { key }
            custody.delete(id); assertFalse(prefs.contains(entry))
            assertTrue(prefs.contains("quantum.successor.material.v1.$other"))
            custody.deleteAll(); assertTrue(prefs.all.isEmpty())
        } finally { context.deleteSharedPreferences(name) }
    }

    @Test fun uncertainCommitBlocksReplacementAndCanBeDurablyReloaded() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "successor-uncertain-${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        var injectedCommits = 0
        val failing = object : SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor {
                val editor = prefs.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        editor.putString(key, value); return this
                    }
                    override fun commit(): Boolean { injectedCommits++; editor.commit(); return false }
                }
            }
        }
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val authority = QuantumSelfPayAuthority(3, ByteArray(32) { 43 }, ByteArray(1312), ByteArray(32))
        val birth = TosQuantumSelfPayIdentity(TosQuantumSelfPayWallet(authority)); val current = QuantumSelfPayState(authority)
        val id = UUID.randomUUID(); val root = ByteArray(32) { 1 }; val file = ByteArray(32) { 2 }
        try {
            assertThrows(IllegalStateException::class.java) { QuantumSuccessorMaterialVault(failing).generate(id, birth, current, root, file, key) }
            assertEquals(1, injectedCommits)
            assertThrows(IllegalStateException::class.java) { QuantumSuccessorMaterialVault(prefs).generate(id, birth, current, root, file, key) }
            assertEquals(birth, QuantumSuccessorMaterialVault(prefs).inspect(id, birth, current, root, file, key).inspection.birth)
        } finally { context.deleteSharedPreferences(name) }
    }
    @Test fun fundedInspectionRetainsOriginalCiphertextAndRejectsOtherTransitions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "successor-funded-${UUID.randomUUID()}"; val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val old = QuantumSelfPayAuthority(3, ByteArray(32) { 43 }, ByteArray(1312) { 1 }, ByteArray(32) { 2 })
        val birth = TosQuantumSelfPayIdentity(TosQuantumSelfPayWallet(old)); val id = UUID.randomUUID()
        val before = QuantumSelfPayState(old, seqno = 7, retired = 2, epoch = 4u, primaryNonce = 3u, rescueNonce = 9u)
        val after = QuantumSelfPayState(old, seqno = 8, retired = 2, epoch = 4u, primaryNonce = 3u, rescueNonce = 10u)
        val root = ByteArray(32) { 1 }; val file = ByteArray(32) { 2 }; val vault = QuantumSuccessorMaterialVault(prefs)
        try {
            val staged = vault.generate(id, birth, before, root, file, key)
            val original = prefs.getString("quantum.successor.material.v1.$id", null)
            val checked = vault.inspectAfterFunding(id, birth, before, after, root, file, key,
                staged.inspection.encryptedDigest, staged.inspection.snapshot)
            assertEquals(id, checked.walletId)
            assertArrayEquals(staged.inspection.encryptedDigest, checked.inspection.encryptedDigest)
            assertArrayEquals(staged.inspection.snapshot.hash, checked.inspection.snapshot.hash)
            assertEquals(original, prefs.getString("quantum.successor.material.v1.$id", null))
            assertThrows(IllegalArgumentException::class.java) { vault.inspect(id, birth, after, root, file, key) }
            for (wrong in listOf(
                QuantumSelfPayState(old, seqno = 9, retired = 2, epoch = 4u, primaryNonce = 3u, rescueNonce = 10u),
                QuantumSelfPayState(old, seqno = 8, retired = 2, epoch = 4u, primaryNonce = 3u, rescueNonce = 11u),
                QuantumSelfPayState(old, seqno = 8, retired = 2, epoch = 5u, primaryNonce = 3u, rescueNonce = 10u),
                QuantumSelfPayState(old, seqno = 8, retired = 0, epoch = 4u, primaryNonce = 3u, rescueNonce = 10u),
                QuantumSelfPayState(old, seqno = 8, retired = 2, epoch = 4u, primaryNonce = 4u, rescueNonce = 10u))) {
                assertThrows(IllegalArgumentException::class.java) {
                    vault.inspectAfterFunding(id, birth, before, wrong, root, file, key, staged.inspection.encryptedDigest, staged.inspection.snapshot)
                }
            }
            assertThrows(IllegalArgumentException::class.java) {
                vault.inspectAfterFunding(id, birth, before, after, ByteArray(32) { 8 }, file, key, staged.inspection.encryptedDigest, staged.inspection.snapshot)
            }
            assertThrows(IllegalArgumentException::class.java) {
                vault.inspectAfterFunding(id, birth, before, after, root, file, key, ByteArray(32) { 8 }, staged.inspection.snapshot)
            }
            val other = QuantumSelfPayAuthority(old.globalId, old.namespace, old.primaryKey, old.rescueKey, QuantumPolicy.REQUIRED)
            assertThrows(IllegalArgumentException::class.java) {
                vault.inspectAfterFunding(id, birth, before, after, root, file, key, staged.inspection.encryptedDigest, other)
            }
            assertEquals(original, prefs.getString("quantum.successor.material.v1.$id", null))
        } finally { context.deleteSharedPreferences(name) }
    }
}
