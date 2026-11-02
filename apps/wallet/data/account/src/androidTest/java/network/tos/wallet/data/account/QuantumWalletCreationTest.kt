package network.tos.wallet.data.account

import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import network.tos.security.pq.*
import network.tos.wallet.data.account.pq.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import javax.crypto.KeyGenerator

@RunWith(AndroidJUnit4::class)
class QuantumWalletCreationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    // Public native-TOS test mnemonic, never a funded or user wallet.
    private val words = "twice half glass steak version only friend bone addict during joke offer fog punch panel true regular letter topple often afraid hint ready exhibit".split(" ")
    private fun read(name: String) = instrumentation.context.assets.open("quantum-self-pay-live/$name").use { it.readBytes() }
    private val fixtureNow get() = JSONObject(read("manifest.json").toString(Charsets.UTF_8)).getLong("controlled_now")
    private fun profile(id: Int = 1): QuantumNetworkProfile {
        val anchor = JSONObject(read("anchor.json").toString(Charsets.UTF_8))
        return QuantumNetworkProfile(id, anchor.getString("root_hash"), anchor.getString("file_hash"))
    }
    private fun prove(id: UUID, profile: QuantumNetworkProfile): QuantumSelfPayNetwork {
        val names = listOf("masterchain-info.tl", "chain-0000.tl", "config.tl")
        val replies = names.map { read("policy/material/$it") }
        var calls = 0
        val session = QuantumProofSession(context, id, profile.anchor())
        val proof = session.enrollBound(QuantumSelfPayNetwork.request(300), fixtureNow, QuantumProofTransport { _, capacity ->
            check(calls < replies.size)
            replies[calls++].also { check(it.size <= capacity) }
        })
        assertEquals(3, calls)
        return QuantumSelfPayNetwork.bind(proof, profile.globalId, fixtureNow, 300)
    }
    private fun clean(id: UUID) {
        File(context.noBackupFilesDir, "quantum-proof-checkpoints/$id").deleteRecursively()
        File(context.noBackupFilesDir, "quantum-creation/$id.json").delete()
    }

    @Test fun nativeProofCreationPersistsBothRolesAndReloadablePublicIdentity() = runBlocking {
        var record: QuantumCreationRecord? = null
        val custody = QuantumCreationCustody(context)
        val events = mutableListOf<String>()
        val seeds = mutableListOf<ByteArray>()
        var id: UUID? = null
        val creation = QuantumWalletCreation(
            network = { uuid, selected -> id = uuid; prove(uuid, selected) },
            saveRecord = { record = it; it.save(context); events.add("record") },
            saveMnemonic = { uuid, mnemonic -> custody.saveMnemonic(uuid, mnemonic); events.add("mnemonic") },
            storeRole = { uuid, role, binding, seed ->
                seeds.add(seed); custody.store(uuid, role, binding, seed).also { events.add(role.name) }
            }, now = { fixtureNow },
        )
        try {
            val walletId = creation.create(words, profile()) { value ->
                events.add("publish")
                assertEquals(words, QuantumCreationCustody(context).mnemonic(value.id)!!.toList())
                value.id
            }
            assertEquals(id, walletId)
            assertEquals(listOf("record", "mnemonic", "PRIMARY", "RESCUE", "publish"), events)
            assertTrue(seeds.all { seed -> seed.all { it == 0.toByte() } })
            val value = record!!
            val saved = File(context.noBackupFilesDir, "quantum-creation/$walletId.json").readBytes()
            val decoded = QuantumCreationRecord.decode(saved)
            assertEquals(value.identity, decoded.identity)
            assertEquals(value.profile.rootHash, decoded.profile.rootHash)
            assertEquals(value.profile.fileHash, decoded.profile.fileHash)
            assertFalse(saved.toString(Charsets.UTF_8).contains(words.joinToString(" ")))
            assertNotNull(runCatching { value.save(context) }.exceptionOrNull())
            assertArrayEquals(saved, File(context.noBackupFilesDir, "quantum-creation/$walletId.json").readBytes())
            assertNotNull(runCatching { custody.saveMnemonic(walletId, words) }.exceptionOrNull())
            custody.delete(walletId)
            assertNull(QuantumCreationCustody(context).mnemonic(walletId))
        } finally { id?.let { custody.delete(it); clean(it) } }
    }

    @Test fun everyStorageFailureStopsPublicationAndWipesDerivedSeeds() = runBlocking {
        val proofId = UUID.randomUUID()
        try {
            val network = prove(proofId, profile())
            val steps = listOf("record", "mnemonic", "PRIMARY", "RESCUE", "publish")
            for (failure in steps) {
                val events = mutableListOf<String>()
                val seeds = mutableListOf<ByteArray>()
                fun step(value: String) { events.add(value); if (value == failure) error("injected $failure") }
                val creation = QuantumWalletCreation(
                    network = { _, _ -> network }, saveRecord = { step("record") },
                    saveMnemonic = { _, _ -> step("mnemonic") },
                    storeRole = { _, role, _, seed -> seeds.add(seed); step(role.name); QuantumCrypto.publicKey(role, seed) },
                    now = { fixtureNow },
                )
                assertEquals("injected $failure", runCatching { creation.create(words, profile()) { step("publish") } }.exceptionOrNull()?.message)
                assertEquals(steps.take(steps.indexOf(failure) + 1), events)
                assertTrue(seeds.all { seed -> seed.all { it == 0.toByte() } })
            }
        } finally { clean(proofId) }
    }

    @Test fun staleProofOrWrongStoredKeyCannotPublish() = runBlocking {
        val proofId = UUID.randomUUID()
        try {
            val network = prove(proofId, profile())
            for (wrongKey in listOf(false, true)) {
                var now = fixtureNow
                var published = false
                val creation = QuantumWalletCreation(network = { _, _ -> network }, saveRecord = {}, saveMnemonic = { _, _ -> },
                    storeRole = { _, role, _, seed ->
                        val key = QuantumCrypto.publicKey(role, seed)
                        if (role == QuantumRole.RESCUE) {
                            if (wrongKey) key[0] = (key[0].toInt() xor 1).toByte() else now += 301
                        }
                        key
                    }, now = { now })
                val error = runCatching { creation.create(words, profile()) { published = true } }.exceptionOrNull()
                assertEquals(if (wrongKey) "Stored RESCUE differs from enrollment" else "Proof freshness refused", error?.message)
                assertFalse(published)
            }
        } finally { clean(proofId) }
    }

    @Test fun unauthenticatedOrWrongNetworkWritesNothing() = runBlocking {
        val proofId = UUID.randomUUID()
        try {
            val network = prove(proofId, profile())
            for (unavailable in listOf(true, false)) {
                var writes = 0
                val creation = QuantumWalletCreation(network = { _, _ -> if (unavailable) error("unavailable") else network },
                    saveRecord = { writes++ }, saveMnemonic = { _, _ -> writes++ },
                    storeRole = { _, role, _, seed -> writes++; QuantumCrypto.publicKey(role, seed) }, now = { fixtureNow })
                assertEquals(if (unavailable) "unavailable" else "Creation network global ID mismatch",
                    runCatching { creation.create(words, profile(2)) { writes++ } }.exceptionOrNull()?.message)
                assertEquals(0, writes)
            }
        } finally { clean(proofId) }
    }

    @Test fun profileAndPublicRecordRejectMalformedNetworkAndDerivation() {
        val valid = profile()
        for (hash in listOf("0".repeat(64), "a".repeat(63), "g".repeat(64))) {
            assertNotNull(runCatching { QuantumNetworkProfile(1, hash, valid.fileHash) }.exceptionOrNull())
        }
        assertEquals(valid.rootHash, QuantumNetworkProfile(1, valid.rootHash.uppercase(), valid.fileHash).rootHash)
        for (id in listOf("1", 1.5, 2147483648L)) {
            assertNotNull(runCatching { QuantumNetworkProfile.parse(valid.json().put("global_id", id)) }.exceptionOrNull())
        }
        val identity = network.tos.blockchain.ton.contract.TosQuantumSelfPayIdentity(
            network.tos.blockchain.ton.contract.TosQuantumSelfPayWallet(
                network.tos.blockchain.ton.contract.QuantumSelfPayAuthority(1, ByteArray(32) { 0x42 }, ByteArray(1312), ByteArray(32))))
        val record = QuantumCreationRecord(UUID.randomUUID(), valid, identity)
        for (field in listOf("version", "account", "generation")) {
            val altered = JSONObject(record.encode().toString(Charsets.UTF_8)).put(field, 3).toString().toByteArray()
            assertNotNull(runCatching { QuantumCreationRecord.decode(altered) }.exceptionOrNull())
        }
        assertNotNull(runCatching { QuantumCreationRecord(record.id, profile(2), identity) }.exceptionOrNull())
    }

    @Test fun resumePreservesIdentityAndExistingCiphertextWhileRepairingMissingRole() = runBlocking {
        val proofId = UUID.randomUUID()
        val prefsName = "quantum-resume-${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val custody = QuantumCreationCustody(prefs) { key }
        var record: QuantumCreationRecord? = null
        try {
            val network = prove(proofId, profile())
            val initial = QuantumWalletCreation(network = { _, _ -> network },
                saveRecord = { record = it; it.save(context) }, saveMnemonic = { id, mnemonic -> custody.saveMnemonic(id, mnemonic) },
                storeRole = { id, role, binding, seed ->
                    if (role == QuantumRole.RESCUE) error("injected interruption")
                    custody.store(id, role, binding, seed)
                }, now = { fixtureNow })
            assertEquals("injected interruption", runCatching { initial.create(words, profile()) { fail("Early publication") } }.exceptionOrNull()?.message)
            val original = QuantumCreationRecord.load(context, record!!.id)
            assertEquals(listOf(original.id), custody.pendingIds())
            val primaryName = "quantum.seed.v1.1.${original.id}"
            val primaryCiphertext = prefs.getString(primaryName, null)
            assertNotNull(primaryCiphertext)
            assertFalse(prefs.contains("quantum.seed.v1.2.${original.id}"))
            val restored = QuantumCreationCustody(prefs) { key }
            val resumed = QuantumWalletCreation(network = { id, selected ->
                assertEquals(original.id, id); assertEquals(original.profile.rootHash, selected.rootHash); network
            }, saveRecord = { fail("Resume rewrote the original enrollment") },
                saveMnemonic = { id, mnemonic -> restored.ensureMnemonic(id, mnemonic) },
                storeRole = { id, role, binding, seed -> restored.ensureRole(id, role, binding, seed) }, now = { fixtureNow })
            repeat(2) {
                val published = resumed.resume(original, restored.mnemonic(original.id)!!.toList()) { it }
                assertEquals(original.id, published.id)
                assertEquals(original.identity, published.identity)
                assertEquals(primaryCiphertext, prefs.getString(primaryName, null))
            }
            val authority = original.identity.wallet.initialAuthority
            val binding = QuantumSeedVault.Context(authority.namespace, authority.globalId, 0, 0)
            val roles = QuantumSeedVault(prefs)
            assertArrayEquals(authority.primaryKey, roles.publicKey(original.id.toString(), QuantumRole.PRIMARY, binding, key))
            assertArrayEquals(authority.rescueKey, roles.publicKey(original.id.toString(), QuantumRole.RESCUE, binding, key))
            restored.markComplete(original.id)
            assertTrue(restored.pendingIds().isEmpty())
        } finally { record?.let { clean(it.id) }; clean(proofId); context.deleteSharedPreferences(prefsName) }
    }

    @Test fun resumeRefusesAnotherMnemonicAndDoesNotReplaceConflictingCustody() = runBlocking {
        val proofId = UUID.randomUUID()
        val prefsName = "quantum-resume-conflict-${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val custody = QuantumCreationCustody(prefs) { key }
        try {
            val network = prove(proofId, profile())
            val initial = QuantumWalletCreation(network = { _, _ -> network }, saveRecord = {},
                saveMnemonic = { id, mnemonic -> custody.saveMnemonic(id, mnemonic) },
                storeRole = { id, role, binding, seed -> custody.store(id, role, binding, seed) }, now = { fixtureNow })
            val original = initial.create(words, profile()) { it }
            val before = prefs.all.toMap()
            val resumed = QuantumWalletCreation(network = { _, _ -> network }, saveRecord = { fail("Unexpected rewrite") },
                saveMnemonic = { id, mnemonic -> custody.ensureMnemonic(id, mnemonic) },
                storeRole = { id, role, binding, seed -> custody.ensureRole(id, role, binding, seed) }, now = { fixtureNow })
            val another = "coffee glad rail dry pink piano allow announce system shrug term return vague crater silly state quick glow wrestle wink tail derive device recall".split(" ")
            assertEquals("Derived wallet differs from original enrollment", runCatching { resumed.resume(original, another) {} }.exceptionOrNull()?.message)
            assertEquals(before, prefs.all)
            val authority = original.identity.wallet.initialAuthority
            val binding = QuantumSeedVault.Context(authority.namespace, authority.globalId, 0, 0)
            val name = "quantum.seed.v1.1.${original.id}"
            assertTrue(prefs.edit().remove(name).commit())
            QuantumSeedVault(prefs).importAndWipe(original.id.toString(), QuantumRole.PRIMARY, binding, key, ByteArray(32) { 99 })
            val conflicting = prefs.getString(name, null)
            assertEquals("Creation key differs from enrollment", runCatching { resumed.resume(original, words) {} }.exceptionOrNull()?.message)
            assertEquals(conflicting, prefs.getString(name, null))
        } finally { clean(proofId); context.deleteSharedPreferences(prefsName) }
    }

    @Test fun mismatchedPublicEnrollmentCannotReconstructAbsentCustodyOrPublish() = runBlocking {
        val proofId = UUID.randomUUID()
        try {
            val network = prove(proofId, profile())
            var writes = 0
            val original = QuantumCreationRecord(UUID.randomUUID(), profile(), network.makeIdentity(ByteArray(1312), ByteArray(32), now = fixtureNow))
            val resumed = QuantumWalletCreation(network = { _, _ -> network }, saveRecord = { writes++ },
                saveMnemonic = { _, _ -> writes++ },
                storeRole = { _, role, _, seed -> writes++; QuantumCrypto.publicKey(role, seed) }, now = { fixtureNow })
            assertEquals("Derived wallet differs from original enrollment", runCatching { resumed.resume(original, words) { writes++ } }.exceptionOrNull()?.message)
            assertEquals(0, writes)
        } finally { clean(proofId) }
    }

    @Test fun custodyCommitFailuresNeverReportSuccessAndCleanupIsScoped() {
        val name = "quantum-custody-test-${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        var fail = false
        val failing = object : SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor {
                val editor = prefs.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?) = apply { editor.putString(key, value) }
                    override fun remove(key: String?) = apply { editor.remove(key) }
                    override fun commit(): Boolean = if (fail) false else editor.commit()
                }
            }
        }
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val custody = QuantumCreationCustody(failing) { key }
        val id = UUID.randomUUID()
        val binding = QuantumSeedVault.Context(ByteArray(32) { 0x42 }, 1, 0, 0)
        try {
            fail = true
            assertNotNull(runCatching { custody.saveMnemonic(id, words) }.exceptionOrNull())
            assertNull(custody.mnemonic(id))
            val seed = ByteArray(32) { 7 }
            assertNotNull(runCatching { custody.store(id, QuantumRole.PRIMARY, binding, seed) }.exceptionOrNull())
            assertTrue(seed.all { it == 0.toByte() })
            fail = false
            custody.saveMnemonic(id, words)
            custody.store(id, QuantumRole.PRIMARY, binding, ByteArray(32) { 7 })
            custody.store(id, QuantumRole.RESCUE, binding, ByteArray(48) { 9 })
            prefs.edit().putString("unrelated", "keep").commit()
            fail = true
            assertNotNull(runCatching { custody.delete(id) }.exceptionOrNull())
            assertEquals(words, custody.mnemonic(id)!!.toList())
            assertNotNull(runCatching { custody.deleteAll() }.exceptionOrNull())
            fail = false
            custody.deleteAll()
            assertEquals(setOf("unrelated"), prefs.all.keys)
        } finally { context.deleteSharedPreferences(name) }
    }
}
