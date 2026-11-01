package network.tos.wallet.data.account

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import network.tos.blockchain.ton.contract.*
import network.tos.security.pq.QuantumSuccessorMaterialVault
import network.tos.wallet.data.account.pq.QuantumCreationCustody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import javax.crypto.KeyGenerator

/** Public local identities and software AES only, not Keystore or live migration acceptance. */
@RunWith(AndroidJUnit4::class)
class QuantumSuccessorMaterialTest {
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
}
