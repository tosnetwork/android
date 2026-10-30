package network.tos.wallet.data.account

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import network.tos.blockchain.ton.contract.*
import network.tos.blockchain.ton.extensions.storeAddress
import network.tos.blockchain.ton.extensions.toByteArray
import network.tos.security.pq.QuantumProofSession
import network.tos.security.pq.QuantumProofTransport
import network.tos.security.pq.QuantumVerifiedRead
import network.tos.wallet.data.account.pq.QuantumSelfPayAccount
import network.tos.wallet.data.account.pq.QuantumSelfPayNetwork
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.ton.cell.Cell
import org.ton.cell.buildCell
import java.io.File
import java.math.BigInteger
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class QuantumSelfPayAccountTest {
    private fun wallet() = TosQuantumSelfPayWallet(QuantumSelfPayAuthority(1, ByteArray(32) { 0x42 }, ByteArray(1312), ByteArray(32)))
    @Test fun freshMasterchainDoesNotRefreshStaleShardState() {
        QuantumSelfPayAccount.validateTime(1000, 700, 1000, 300)
        for ((master, shard, now) in listOf(Triple(1000L, 699L, 1000L), Triple(1000L, 1001L, 1000L), Triple(1061L, 1061L, 1000L))) {
            assertEquals("Account proof freshness refused", runCatching {
                QuantumSelfPayAccount.validateTime(master, shard, now, 300)
            }.exceptionOrNull()?.message)
        }
    }
    private fun account(wallet: TosQuantumSelfPayWallet, frozen: Boolean = false, tail: Boolean = false,
                        state: QuantumSelfPayState? = null, code: Cell = wallet.code) = buildCell {
        storeBit(true); storeAddress(wallet.address); storeUInt(0, 6)
        storeUInt(if (frozen) 0 else 1, 3)
        if (!frozen) storeBytes(ByteArray(32))
        storeUInt(100, 32)
        storeBit(true); storeUInt(1, 4); storeUInt(128, 8); storeUInt(0, 64)
        storeUInt(15, 4); storeBytes(ByteArray(15) { 0xff.toByte() }); storeBit(false)
        if (state != null) {
            storeBit(true); storeUInt(0, 2); storeBit(true); storeRef(code)
            storeBit(true); storeRef(state.cell); storeBit(false)
        } else {
            storeBit(false); storeBit(frozen)
            if (frozen) storeBytes(wallet.stateInit.hash().toByteArray())
        }
        if (tail) storeBit(false)
    }.toByteArray()

    @Test fun frozenAndUninitializedAreDistinctAndPreserveUnsignedAmounts() {
        val w = wallet()
        for (frozen in listOf(false, true)) {
            val s = QuantumSelfPayAccount.decode(account(w, frozen), w)
            assertEquals(BigInteger.ONE.shiftLeft(120).subtract(BigInteger.ONE), s.balance)
            assertEquals(BigInteger.valueOf(128), s.storageDebt); assertEquals(100L, s.lastPaid)
            when (val status = s.status) {
                is QuantumSelfPayAccount.Status.Frozen -> {
                    assertTrue(frozen)
                    assertArrayEquals(w.stateInit.hash().toByteArray(), status.stateHash)
                    status.stateHash.fill(0)
                    assertArrayEquals(w.stateInit.hash().toByteArray(), status.stateHash)
                }
                QuantumSelfPayAccount.Status.Uninitialized -> assertFalse(frozen)
                else -> fail("Unexpected account status")
            }
            assertEquals("Trailing account data", runCatching { QuantumSelfPayAccount.decode(account(w, frozen, tail = true), w) }.exceptionOrNull()?.message)
        }
    }

    @Test fun activeUsesCurrentAuthorityButRetainsBirthAddressAndCode() {
        val w = wallet()
        val successor = QuantumSelfPayAuthority(1, w.initialAuthority.namespace, ByteArray(1312) { 1 }, ByteArray(32) { 2 }, QuantumPolicy.REQUIRED)
        val state = QuantumSelfPayState(successor, seqno = 4, epoch = 1u)
        val s = QuantumSelfPayAccount.decode(account(w, state = state), w)
        val active = s.status as QuantumSelfPayAccount.Status.Active
        assertArrayEquals(successor.hash, active.state.authority.hash); assertEquals(4L, active.state.seqno)
        assertEquals("Account code mismatch", runCatching { QuantumSelfPayAccount.decode(account(w, state = state, code = buildCell {}), w) }.exceptionOrNull()?.message)
        assertEquals("Wallet network or ID changed", runCatching {
            QuantumSelfPayAccount.decode(account(w, state = QuantumSelfPayState(successor, walletId = 1)), w)
        }.exceptionOrNull()?.message)
    }

    /** New self-pay chain capture, native-verified at recorded time; not current live/payment acceptance. */
    @Test fun nativeProofsBindAllThreeBirthIdentitiesAndRejectTransplants() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        fun read(path: String) = assets.open("quantum-self-pay-live/$path").use { it.readBytes() }
        val now = JSONObject(read("manifest.json").toString(Charsets.UTF_8)).getLong("controlled_now")
        val records = JSONObject(read("public-self-pay-accounts.json").toString(Charsets.UTF_8)).getJSONObject("accounts")
        fun identity(role: String) = TosQuantumSelfPayIdentity.fromBoc(Base64.decode(records.getJSONObject(role).getString("identity_boc"), Base64.NO_WRAP))
        val directories = mutableListOf<File>()
        fun prove(role: String, corrupt: Boolean = false): QuantumVerifiedRead {
            val id = UUID.randomUUID()
            directories.add(File(instrumentation.targetContext.noBackupFilesDir, "quantum-proof-checkpoints/$id"))
            val session = QuantumProofSession(instrumentation.targetContext, id, read("anchor.json"))
            val names = if (role == "policy") listOf("masterchain-info.tl", "chain-0000.tl", "config.tl") else listOf("chain-0000.tl", "account.tl")
            val replies = names.map { read("$role/material/$it") }
            // Corrupt the returned masterchain root, not ignored TL padding.
            if (corrupt) replies.last().let { it[20] = (it[20].toInt() xor 1).toByte() }
            var calls = 0
            val result = session.enrollBound(read("$role/request.json"), now, QuantumProofTransport { _, capacity ->
                check(calls < replies.size)
                replies[calls++].also { check(it.size <= capacity) }
            })
            assertEquals(names.size, calls)
            return result
        }
        try {
            val policy = prove("policy")
            val network = QuantumSelfPayNetwork.bind(policy, 1, now, 300)
            // Absence has no account BOC: its address must be bound by the proof boundary itself.
            for (role in listOf("absent", "prefunded", "active")) {
                val birth = identity(role)
                val proof = prove(role)
                network.requireSameCheckpoint(proof, now)
                assertEquals(birth, network.makeIdentity(birth.wallet.initialAuthority.primaryKey,
                    birth.wallet.initialAuthority.rescueKey, birth.wallet.walletId, now))
                val account = QuantumSelfPayAccount.bind(birth, proof, now, 300)
                val s = account.snapshot(now)
                when (role) {
                    "active" -> {
                        assertEquals(birth.wallet.initialData.hash(), (s.status as QuantumSelfPayAccount.Status.Active).state.cell.hash())
                        assertEquals(BigInteger("1000000000000000"), s.balance)
                    }
                    "prefunded" -> { assertEquals(QuantumSelfPayAccount.Status.Uninitialized, s.status); assertEquals(BigInteger("10000000000"), s.balance) }
                    "absent" -> { assertEquals(QuantumSelfPayAccount.Status.Absent, s.status); assertEquals(BigInteger.ZERO, s.balance) }
                }
                assertEquals("Proof freshness refused", runCatching { account.snapshot(now + 301) }.exceptionOrNull()?.message)
                assertEquals("Proof freshness refused", runCatching { QuantumSelfPayAccount.bind(birth, proof, now + 301, 300) }.exceptionOrNull()?.message)
                val other = identity(if (role == "active") "prefunded" else "active")
                assertEquals("Proof account binding refused", runCatching { QuantumSelfPayAccount.bind(other, proof, now, 300) }.exceptionOrNull()?.message)
            }
            assertNotNull(runCatching { prove("active", corrupt = true) }.exceptionOrNull())
        } finally { directories.forEach { it.deleteRecursively() } }
    }
}
