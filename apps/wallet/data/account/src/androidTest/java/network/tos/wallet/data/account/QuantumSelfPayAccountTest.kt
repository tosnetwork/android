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
import network.tos.wallet.data.account.pq.QuantumPreparedRecoveryControl
import network.tos.wallet.data.account.pq.QuantumAccountReader
import network.tos.wallet.data.account.pq.QuantumNetworkProfile
import network.tos.wallet.data.account.pq.QuantumRecoveryControlJournal
import network.tos.wallet.data.account.pq.QuantumPaymentOutbox
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
        fun prove(role: String, corrupt: Boolean = false, recoveryBase: Boolean = false): QuantumVerifiedRead {
            val id = UUID.randomUUID()
            directories.add(File(instrumentation.targetContext.noBackupFilesDir, "quantum-proof-checkpoints/$id"))
            val session = QuantumProofSession(instrumentation.targetContext, id, read("anchor.json"))
            val names = if (role == "policy") listOf("masterchain-info.tl", "chain-0000.tl", "config.tl") else listOf("chain-0000.tl", "account.tl")
            val replies = names.map { read("$role/material/$it") }
            // Corrupt the returned masterchain root, not ignored TL padding.
            if (corrupt) replies.last().let { it[20] = (it[20].toInt() xor 1).toByte() }
            var calls = 0
            val request = if (recoveryBase) JSONObject(read("$role/request.json").toString(Charsets.UTF_8))
                .put("config_params", org.json.JSONArray(listOf(8, 19))).toString().toByteArray() else read("$role/request.json")
            val result = session.enrollBound(request, now, QuantumProofTransport { _, capacity ->
                check(calls < replies.size)
                replies[calls++].also { check(it.size <= capacity) }
            })
            assertEquals(names.size, calls)
            return result
        }
        try {
            val policy = prove("policy")
            val recoveryBase = prove("policy", recoveryBase = true)
            assertThrows(IllegalStateException::class.java) { recoveryBase.provenConfigParam(48) }
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
                val anchor = JSONObject(read("anchor.json").toString(Charsets.UTF_8))
                val profile = QuantumNetworkProfile(1, anchor.getString("root_hash"), anchor.getString("file_hash"))
                val observation = QuantumAccountReader.Observation(UUID.randomUUID(), birth, profile, recoveryBase, proof, s)
                if (role == "active") {
                    for (kind in QuantumPreparedRecoveryControl.Kind.entries) {
                        val control = QuantumPreparedRecoveryControl(observation, kind, now, 120)
                        assertEquals(QuantumAuthRole.RESCUE, control.request.role)
                        assertEquals("TOS-QSP-SLH128S-v1", control.request.context)
                        assertArrayEquals(birth.wallet.initialAuthority.rescueKey, control.signingPublicKey)
                        control.requireRevalidated(observation, now)
                        assertThrows(IllegalArgumentException::class.java) {
                            control.requireRevalidated(observation.copy(id = UUID.randomUUID()), now)
                        }
                        assertThrows(IllegalStateException::class.java) { control.requireFresh(control.expiresAt) }
                        val root = File(instrumentation.targetContext.noBackupFilesDir, "control-journal-test-${UUID.randomUUID()}")
                        check(root.mkdir())
                        val isolatedContext = object : android.content.ContextWrapper(instrumentation.targetContext) {
                            override fun getNoBackupFilesDir() = root
                            override fun getApplicationContext(): android.content.Context = this
                        }
                        try {
                            val journal = QuantumRecoveryControlJournal(isolatedContext)
                            assertThrows(IllegalStateException::class.java) { journal.reserve(control, now) }
                            QuantumPaymentOutbox(isolatedContext).isolateRecoveredIdentity(birth)
                            val entry = journal.reserve(control, now)
                            assertThrows(IllegalStateException::class.java) {
                                entry.next(QuantumRecoveryControlJournal.Phase.SIGNED, ByteArray(7856))
                            }
                            assertThrows(IllegalArgumentException::class.java) {
                                QuantumRecoveryControlJournal.Entry.decode(entry.encode() + byteArrayOf(0))
                            }
                            assertArrayEquals(control.request.digest, QuantumRecoveryControlJournal(isolatedContext).pending(birth)!!.requestHash)
                            assertThrows(IllegalStateException::class.java) { journal.reserve(control, now) }
                            var calls = 0
                            assertThrows(IllegalStateException::class.java) {
                                journal.sign(control, observation, now, UUID.randomUUID()) { calls++; ByteArray(0) }
                            }
                            assertEquals(0, calls)
                            assertThrows(java.io.IOException::class.java) {
                                journal.sign(control, observation, now, entry.operation) {
                                    calls++
                                    // Read only public journal bytes: callback must start after SIGNING was written.
                                    val stored = File(root, "quantum-recovery-controls").listFiles()!!.single { it.name.endsWith(".qrc") }.readBytes()
                                    assertEquals(QuantumRecoveryControlJournal.Phase.SIGNING.ordinal, stored[4].toInt())
                                    throw java.io.IOException("Injected uncertain signing")
                                }
                            }
                            assertEquals(1, calls)
                            val restarted = QuantumRecoveryControlJournal(isolatedContext)
                            assertEquals(QuantumRecoveryControlJournal.Phase.SIGNING, restarted.pending(birth)!!.phase)
                            assertThrows(IllegalStateException::class.java) {
                                restarted.sign(control, observation, now, entry.operation) { calls++; ByteArray(0) }
                            }
                            assertEquals(1, calls)
                        } finally { check(root.deleteRecursively()) }
                        for (point in listOf("before_file_sync", "before_rename", "before_directory_sync")) {
                            val failureRoot = File(instrumentation.targetContext.noBackupFilesDir, "control-fault-${UUID.randomUUID()}")
                            check(failureRoot.mkdir())
                            val failureContext = object : android.content.ContextWrapper(instrumentation.targetContext) {
                                override fun getNoBackupFilesDir() = failureRoot
                                override fun getApplicationContext(): android.content.Context = this
                            }
                            try {
                                QuantumPaymentOutbox(failureContext).isolateRecoveredIdentity(birth)
                                val initial = QuantumRecoveryControlJournal(failureContext).reserve(control, now)
                                val failing = QuantumRecoveryControlJournal(failureContext) { if (it == point) throw java.io.IOException("Injected persistence failure") }
                                var called = false
                                assertThrows(java.io.IOException::class.java) {
                                    failing.sign(control, observation, now, initial.operation) { called = true; ByteArray(0) }
                                }
                                assertFalse("No signing before durable SIGNING", called)
                                assertThrows(IllegalStateException::class.java) {
                                    QuantumRecoveryControlJournal(failureContext).sign(control, observation, now, initial.operation) { called = true; ByteArray(0) }
                                }
                                assertFalse(called)
                            } finally { check(failureRoot.deleteRecursively()) }
                        }
                    }
                } else {
                    // A forged Active snapshot cannot substitute for the actual authenticated account.
                    val forged = observation.copy(snapshot = QuantumSelfPayAccount.Snapshot(
                        QuantumSelfPayAccount.Status.Active(QuantumSelfPayState(birth.wallet.initialAuthority)), s.balance, s.storageDebt, s.lastPaid))
                    assertThrows(IllegalStateException::class.java) {
                        QuantumPreparedRecoveryControl(forged, QuantumPreparedRecoveryControl.Kind.LOCK_PRIMARY, now, 120)
                    }
                }
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
