package network.tos.wallet.data.account

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import network.tos.security.pq.QuantumProofSession
import network.tos.security.pq.QuantumProofTransport
import network.tos.security.pq.QuantumVerifiedRead
import network.tos.wallet.data.account.pq.QuantumSelfPayNetwork
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.ton.cell.buildCell
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class QuantumSelfPayNetworkTest {
    private val network = ByteArray(32) { 0x42 }
    private fun hex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun version(value: Long = 16, tag: Int = 0xc4, tail: Boolean = false) = buildCell {
        storeUInt(tag, 8); storeUInt(value, 32); storeUInt(0, 64)
        if (tail) storeBit(false)
    }
    private fun identity(value: Int = 1, tail: Boolean = false) = buildCell {
        storeInt(value, 32)
        if (tail) storeBit(false)
    }
    private fun policy(retired: Int = 0, deadline: Long? = null, validSpec: Boolean = true) = buildCell {
        storeUInt(0xa1, 8); storeBytes(network); storeUInt(0, 64); storeUInt(retired, 16)
        storeBit(deadline != null)
        if (deadline != null) storeRef(buildCell {
            storeUInt(2, 2); storeUInt(8, 4); storeUInt(1, 8); storeUInt(deadline, 32)
        })
        storeBytes(if (validSpec) hex("5e4380aedc95f8cb72de55f7506de0269b47c03ad1d1ed0e5184c332544262c0") else ByteArray(32))
    }

    @Test fun exactSchemaAndRetirementDeadline() {
        for (globalId in listOf(1, -1, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertArrayEquals(network, QuantumSelfPayNetwork.validate(version(), identity(globalId), policy(), globalId, 100))
        }
        for (v in listOf(version(15), version(tag = 0xc5), version(tail = true))) {
            assertNotNull(runCatching { QuantumSelfPayNetwork.validate(v, identity(), policy(), 1, 100) }.exceptionOrNull())
        }
        for (i in listOf(identity(2), identity(tail = true))) {
            assertNotNull(runCatching { QuantumSelfPayNetwork.validate(version(), i, policy(), 1, 100) }.exceptionOrNull())
        }
        for (p in listOf(policy(2), policy(4), policy(validSpec = false), policy(deadline = 100))) {
            assertNotNull(runCatching { QuantumSelfPayNetwork.validate(version(), identity(), p, 1, 100) }.exceptionOrNull())
        }
        assertArrayEquals(network, QuantumSelfPayNetwork.validate(version(), identity(), policy(deadline = 101), 1, 100))
    }

    @Test fun boundedBootstrapRequest() {
        for (age in listOf(0L, -1L, 3600L, Long.MAX_VALUE)) {
            assertNotNull(runCatching { QuantumSelfPayNetwork.request(age) }.exceptionOrNull())
        }
        val request = JSONObject(QuantumSelfPayNetwork.request(300).toString(Charsets.UTF_8))
        assertEquals("live", request.getString("mode"))
        assertEquals(300L, request.getLong("max_age_seconds"))
        val indices = request.getJSONArray("config_params")
        assertEquals(listOf(8, 19, 48), (0 until indices.length()).map { indices.getInt(it) })
    }

    /** Old capture authenticates configuration identity, not new self-pay execution on that chain. */
    @Test fun nativeProofBindsCreationNetworkAndExpiresBeforeCreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        fun read(name: String) = assets.open(name).use { it.readBytes() }
        val now = JSONObject(read("manifest.json").toString(Charsets.UTF_8)).getLong("controlled_now")
        val fixture = JSONObject(assets.open("tos-quantum-self-pay.json").bufferedReader().use { it.readText() })
        val primary = hex(fixture.getString("primary_key"))
        val rescue = hex(fixture.getString("rescue_key"))
        val directories = mutableListOf<File>()
        fun prove(role: String): QuantumVerifiedRead {
            val id = UUID.randomUUID()
            directories.add(File(instrumentation.targetContext.noBackupFilesDir, "quantum-proof-checkpoints/$id"))
            val session = QuantumProofSession(instrumentation.targetContext, id, read("anchor.json"))
            val names = if (role == "policy") listOf("masterchain-info.tl", "chain-0000.tl", "config.tl")
                        else listOf("chain-0000.tl", "account.tl")
            val replies = names.map { read("$role/material/$it") }
            var calls = 0
            val request = if (role == "policy") QuantumSelfPayNetwork.request(300) else read("$role/request.json")
            val result = session.enrollBound(request, now, QuantumProofTransport { _, capacity ->
                check(calls < replies.size)
                replies[calls++].also { check(it.size <= capacity) }
            })
            assertEquals(names.size, calls)
            return result
        }
        try {
            val proof = prove("policy")
            val account = prove("wallet")
            val bound = QuantumSelfPayNetwork.bind(proof, 1, now, 300)
            assertEquals(1, bound.globalId)
            assertArrayEquals(network, bound.namespace)
            bound.namespace.fill(0)
            assertArrayEquals(network, bound.namespace)
            bound.requireSameCheckpoint(account, now)
            val created = bound.makeIdentity(primary, rescue, now = now)
            created.requireNetwork(1, network)
            assertArrayEquals(primary, created.wallet.initialAuthority.primaryKey)
            assertEquals("Creation network global ID mismatch",
                runCatching { QuantumSelfPayNetwork.bind(proof, 2, now, 300) }.exceptionOrNull()?.message)
            assertNotNull(runCatching { QuantumSelfPayNetwork.bind(account, 1, now, 300) }.exceptionOrNull())
            assertEquals("Proof freshness refused",
                runCatching { QuantumSelfPayNetwork.bind(proof, 1, now + 301, 300) }.exceptionOrNull()?.message)
            assertEquals("Proof freshness refused",
                runCatching { bound.makeIdentity(primary, rescue, now = now + 301) }.exceptionOrNull()?.message)
            assertEquals("Proof freshness refused",
                runCatching { bound.requireSameCheckpoint(account, now + 301) }.exceptionOrNull()?.message)
        } finally { directories.forEach { it.deleteRecursively() } }
    }
}
