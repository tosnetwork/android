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
import network.tos.wallet.data.account.pq.QuantumPreparedRecoveryProbe
import network.tos.wallet.data.account.pq.QuantumRecoveryProbeJournal
import network.tos.wallet.data.account.pq.QuantumPreparedProbeFunding
import network.tos.blockchain.ton.extensions.loadAddress
import org.ton.boc.BagOfCells
import network.tos.security.pq.QuantumCrypto
import network.tos.security.pq.QuantumRole
import network.tos.security.pq.QuantumPurpose
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
                    val primarySeed = ByteArray(QuantumRole.PRIMARY.seedSize) { 23 } // Public, unfunded vectors.
                    val rescueSeed = ByteArray(QuantumRole.RESCUE.seedSize) { 31 }
                    val primary = QuantumCrypto.publicKey(QuantumRole.PRIMARY, primarySeed)
                    val rescue = QuantumCrypto.publicKey(QuantumRole.RESCUE, rescueSeed)
                    val successor = QuantumSelfPayAuthority(1, birth.wallet.initialAuthority.namespace, primary, rescue, QuantumPolicy.REQUIRED)
                    val challenge = ByteArray(32) { 9 }
                    val preparedProbe = QuantumPreparedRecoveryProbe(observation, successor, challenge, now, 120)
                    val digest = preparedProbe.probe.digest
                    challenge.fill(0)
                    assertArrayEquals(digest, preparedProbe.probe.digest)
                    assertNotEquals(preparedProbe.probe.address, birth.address)
                    preparedProbe.requireRevalidated(observation, now)
                    try { verifyProbeJournal(observation, preparedProbe, primarySeed, rescueSeed, now) }
                    finally { primarySeed.fill(0); rescueSeed.fill(0) }
                    assertThrows(IllegalStateException::class.java) { preparedProbe.requireFresh(preparedProbe.expiresAt) }
                    assertThrows(IllegalArgumentException::class.java) {
                        preparedProbe.requireRevalidated(observation.copy(id = UUID.randomUUID()), now)
                    }
                    for (bad in listOf(birth.wallet.initialAuthority,
                        QuantumSelfPayAuthority(2, successor.namespace, primary, rescue, QuantumPolicy.REQUIRED),
                        QuantumSelfPayAuthority(1, ByteArray(32) { 7 }, primary, rescue, QuantumPolicy.REQUIRED))) {
                        assertThrows(IllegalArgumentException::class.java) {
                            QuantumPreparedRecoveryProbe(observation, bad, ByteArray(32) { 9 }, now, 120)
                        }
                    }
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
    private fun verifyProbeJournal(observation: QuantumAccountReader.Observation, plan: QuantumPreparedRecoveryProbe,
                                   primarySeed: ByteArray, rescueSeed: ByteArray, now: Long) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        for (failure in listOf(null, "before_file_sync", "before_directory_sync", "callback")) {
            val root = File(base.cacheDir, "probe-journal-${UUID.randomUUID()}").apply { check(mkdir()) }
            val context = object : android.content.ContextWrapper(base) {
                override fun getNoBackupFilesDir() = root
                override fun getApplicationContext(): android.content.Context = this
            }
            try {
                val journal = QuantumRecoveryProbeJournal(context)
                assertThrows(IllegalStateException::class.java) { journal.reserve(plan, ByteArray(32) { 5 }, now) }
                QuantumPaymentOutbox(context).isolateRecoveredIdentity(plan.identity)
                val reserved = journal.reserve(plan, ByteArray(32) { 5 }, now)
                assertThrows(IllegalStateException::class.java) { journal.reserve(plan, ByteArray(32) { 5 }, now) }
                var calls = 0
                val signer: (QuantumRecoveryProbeJournal.Entry) -> Pair<ByteArray, ByteArray> = { entry ->
                    calls++
                    val canonical = File(root, "quantum-recovery-probes").listFiles()!!.single { it.name.endsWith(".qpp") }
                    assertEquals(QuantumRecoveryProbeJournal.Phase.SIGNING, QuantumRecoveryProbeJournal.Entry.decode(canonical.readBytes()).phase)
                    assertArrayEquals(plan.probe.digest, entry.probe.digest)
                    val primary = QuantumCrypto.sign(QuantumRole.PRIMARY, QuantumPurpose.SELF_PAY_POP,
                        primarySeed, plan.successor.primaryKey, entry.probe.digest)
                    if (failure == "callback") { primary.fill(0); error("Injected interrupted second signature") }
                    Pair(primary, QuantumCrypto.sign(QuantumRole.RESCUE, QuantumPurpose.SELF_PAY_POP,
                        rescueSeed, plan.successor.rescueKey, entry.probe.digest))
                }
                assertThrows(IllegalStateException::class.java) { journal.sign(plan.identity, UUID.randomUUID(), observation, now, signer) }
                assertThrows(IllegalStateException::class.java) { journal.sign(plan.identity, reserved.operation, observation, plan.expiresAt, signer) }
                assertEquals(0, calls)
                val faulted = QuantumRecoveryProbeJournal(context) { point -> if (point == failure) error("Injected $point") }
                if (failure == null) {
                    val wrong = Pair(QuantumCrypto.sign(QuantumRole.PRIMARY, QuantumPurpose.POP, primarySeed, plan.successor.primaryKey, plan.probe.digest),
                        QuantumCrypto.sign(QuantumRole.RESCUE, QuantumPurpose.POP, rescueSeed, plan.successor.rescueKey, plan.probe.digest))
                    try { assertThrows(IllegalStateException::class.java) { reserved.next(QuantumRecoveryProbeJournal.Phase.SIGNED, wrong) } }
                    finally { wrong.first.fill(0); wrong.second.fill(0) }
                    faulted.sign(plan.identity, reserved.operation, observation, now, signer)
                    val signed = checkNotNull(QuantumRecoveryProbeJournal(context).pending(plan.identity))
                    assertEquals(QuantumRecoveryProbeJournal.Phase.SIGNED, signed.phase)
                    assertEquals(reserved.operation, signed.operation)
                    assertArrayEquals(signed.signedBody(), QuantumRecoveryProbeJournal.Entry.decode(signed.encode()).signedBody())
                    val funding = QuantumPreparedProbeFunding(signed, observation, BigInteger("10000000"), now, 120)
                    assertEquals(QuantumAuthRole.RESCUE, funding.request.role)
                    assertTrue(funding.expiresAt <= signed.deadline)
                    funding.requireBound(funding.request.request); funding.requireRevalidated(observation, now)
                    // Public unfunded fixture key, not real custody material.
                    val fundingSeed = ByteArray(QuantumRole.RESCUE.seedSize) { 0x22 }
                    val validFundingSignature = QuantumCrypto.sign(QuantumRole.RESCUE, QuantumPurpose.SELF_PAY,
                        fundingSeed, funding.identity.wallet.initialAuthority.rescueKey, funding.request.digest)
                    val wrongFundingDomain = QuantumCrypto.sign(QuantumRole.RESCUE, QuantumPurpose.SELF_PAY_POP,
                        fundingSeed, funding.identity.wallet.initialAuthority.rescueKey, funding.request.digest)
                    try {
                        funding.requireSignature(validFundingSignature)
                        assertThrows(IllegalStateException::class.java) { funding.requireSignature(wrongFundingDomain) }
                        validFundingSignature[0] = (validFundingSignature[0].toInt() xor 1).toByte()
                        assertThrows(IllegalStateException::class.java) { funding.requireSignature(validFundingSignature) }
                    } finally { fundingSeed.fill(0); validFundingSignature.fill(0); wrongFundingDomain.fill(0) }
                    // Resuming must retain the exact request, rather than renewing TTL from the new local time.
                    val restored = QuantumPreparedProbeFunding(signed, observation, funding.amount, now + 1, 120,
                        restoredDeadline = funding.expiresAt)
                    assertEquals(funding.expiresAt, restored.expiresAt)
                    assertEquals(funding.request.request.hash(), restored.request.request.hash())
                    funding.requireBound(restored.request.request)
                    val changed = QuantumPreparedProbeFunding(signed, observation, funding.amount, now + 1, 120,
                        restoredDeadline = funding.expiresAt - 1)
                    assertThrows(IllegalArgumentException::class.java) { funding.requireBound(changed.request.request) }
                    assertThrows(IllegalStateException::class.java) {
                        QuantumPreparedProbeFunding(signed, observation, funding.amount, funding.expiresAt, 120,
                            restoredDeadline = funding.expiresAt)
                    }
                    assertThrows(IllegalStateException::class.java) {
                        QuantumPreparedProbeFunding(signed, observation, funding.amount, now, 120,
                            restoredDeadline = signed.deadline + 1)
                    }
                    val msg = funding.message.beginParse()
                    assertEquals(16, msg.loadUInt(6).toInt()); assertEquals(signed.probe.address, msg.loadAddress())
                    val count = msg.loadUInt(4).toInt(); assertEquals(funding.amount, msg.loadUInt(count * 8))
                    assertFalse(msg.loadBit()); assertEquals(0, msg.loadUInt(4).toInt()); assertEquals(0, msg.loadUInt(4).toInt())
                    assertEquals(BigInteger.ZERO, msg.loadUInt(64)); assertEquals(BigInteger.ZERO, msg.loadUInt(32))
                    assertTrue(msg.loadBit()); assertTrue(msg.loadBit())
                    assertEquals(signed.probe.stateInit.hash(), msg.loadRef().hash())
                    assertTrue(msg.loadBit()); assertEquals(BagOfCells(signed.signedBody()).roots.single().hash(), msg.loadRef().hash())
                    assertEquals(0, msg.remainingBits); assertEquals(msg.refs.size, msg.refsPosition)
                    val action = funding.actions.beginParse()
                    assertEquals(0x0ec3c86dL, action.loadUInt(32).toLong()); assertEquals(3, action.loadUInt(8).toInt())
                    assertEquals(0, action.loadRef().beginParse().remainingBits); assertEquals(funding.message.hash(), action.loadRef().hash())
                    for (amount in listOf(BigInteger.ZERO, BigInteger.valueOf(-1), QuantumPreparedProbeFunding.MAX_FUNDING + BigInteger.ONE)) {
                        assertThrows(IllegalArgumentException::class.java) { QuantumPreparedProbeFunding(signed, observation, amount, now, 120) }
                    }
                    val another = QuantumPreparedProbeFunding(signed, observation, BigInteger("20000000"), now, 120)
                    assertThrows(IllegalArgumentException::class.java) { funding.requireBound(another.request.request) }
                    assertThrows(IllegalStateException::class.java) { funding.requireFresh(funding.expiresAt) }
                    assertThrows(IllegalArgumentException::class.java) { funding.requireRevalidated(observation.copy(id = UUID.randomUUID()), now) }
                } else {
                    assertThrows(IllegalStateException::class.java) { faulted.sign(plan.identity, reserved.operation, observation, now, signer) }
                    if (failure == "before_file_sync") assertThrows(IllegalStateException::class.java) { journal.pending(plan.identity) }
                    else assertEquals(QuantumRecoveryProbeJournal.Phase.SIGNING, journal.pending(plan.identity)!!.phase)
                }
                assertEquals(if (failure == null || failure == "callback") 1 else 0, calls)
                assertThrows(IllegalStateException::class.java) { journal.sign(plan.identity, reserved.operation, observation, now, signer) }
                assertTrue(QuantumPaymentOutbox(context).requiresRecovery(plan.identity))
            } finally { root.deleteRecursively() }
        }
    }
}
