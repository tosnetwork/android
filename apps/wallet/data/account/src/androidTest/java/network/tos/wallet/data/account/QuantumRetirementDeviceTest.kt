package network.tos.wallet.data.account

import android.system.Os
import android.system.OsConstants
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import network.tos.blockchain.ton.contract.*
import network.tos.security.pq.*
import network.tos.wallet.data.account.entities.WalletIdentity
import network.tos.wallet.data.account.pq.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.ton.boc.BagOfCells
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class QuantumRetirementDeviceTest {
    private class Fixture : AutoCloseable {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val id = UUID.randomUUID()
        val operations = mutableSetOf<UUID>()
        fun bytes(name: String) = instrumentation.context.assets.open("quantum-self-pay-live/$name").use { it.readBytes() }
        var now = JSONObject(bytes("manifest.json").toString(Charsets.UTF_8)).getLong("controlled_now")
        private val anchor = JSONObject(bytes("anchor.json").toString(Charsets.UTF_8))
        val profile = QuantumNetworkProfile(1, anchor.getString("root_hash"), anchor.getString("file_hash"))
        val identity = TosQuantumSelfPayIdentity.fromBoc(Base64.decode(JSONObject(bytes("public-self-pay-accounts.json").toString(Charsets.UTF_8))
            .getJSONObject("accounts").getJSONObject("active").getString("identity_boc"), Base64.NO_WRAP))
        val outbox = QuantumPaymentOutbox(context)
        val journal = File(context.noBackupFilesDir, "quantum-payment-outbox/${hex(identity.cell.hash().toByteArray())}.json")
        private val names = listOf("policy/material/masterchain-info.tl", "policy/material/chain-0000.tl", "policy/material/config.tl")
        fun transport() : QuantumProofTransport {
            val replies = (names + listOf("active/material/chain-0000.tl", "active/material/account.tl")).map(::bytes)
            var calls = 0
            return QuantumProofTransport { _, maximum -> replies[calls++].also { check(it.size <= maximum) } }
        }
        fun accounts() = QuantumAccountReader(context, transport()) { now }
        fun reader(transport: QuantumProofTransport = transport()) = QuantumPaymentOutcomeReader(context,
            QuantumAccountReader(context, transport) { now }, QuantumHistoryReader.Query { _, _, _ ->
            error("Expiry cannot infer safety from history")
        }) { now }
        val observation: QuantumAccountReader.Observation
        val shardTime: Long
        init {
            check(!journal.exists()) { "Fixture identity already has an authorization" }
            QuantumCreationRecord(id, profile, identity).save(context)
            var calls = 0
            QuantumProofSession(context, id, profile.anchor()).enrollBound(bytes("policy/request.json"), now,
                QuantumProofTransport { _, maximum -> bytes(names[calls++]).also { check(it.size <= maximum) } })
            observation = accounts().observe(id, identity, intArrayOf(8, 19, 48))
            shardTime = observation.proof.provenAccountTime("0:" + hex(identity.address.address.toByteArray()))
        }
        fun entry(phase: QuantumPaymentOutbox.Phase, deadline: Long): QuantumPaymentOutbox.Entry {
            val intent = QuantumPaymentIntent(identity.address, BigInteger.ONE, false, "expiry fixture")
            val active = observation.snapshot.status as QuantumSelfPayAccount.Status.Active
            // These are the ORIGINAL authorization counters. Current chain counters need not match for expiry.
            val originalState = QuantumSelfPayState(active.state.authority, epoch = 7u, primaryNonce = 9u)
            val request = TosQuantumSelfPayRequest(identity.address, originalState, QuantumAuthRole.PRIMARY, deadline,
                QuantumSelfPayAction.Execute(intent.actions()), deadline - 1, BagOfCells(observation.network.provenConfigParam(48)).roots.single())
            val value = JSONObject().put("version", 1).put("id", UUID.randomUUID().toString())
                .put("identity", WalletIdentity.Quantum(identity).toBase64()).put("profile", profile.json())
                .put("intent", QuantumPaymentOutbox.encodeIntent(intent)).put("deploy", false).put("phase", phase.name)
                .put("request", encode(BagOfCells(request.request).toByteArray())).put("request_hash", encode(request.digest))
                .put("public_key", encode(active.state.authority.primaryKey))
            if (phase in setOf(QuantumPaymentOutbox.Phase.SIGNED, QuantumPaymentOutbox.Phase.SUBMITTING, QuantumPaymentOutbox.Phase.UNKNOWN)) {
                // Canonical regression seed is public; it is never installed in production App custody.
                val sig = QuantumCrypto.sign(QuantumRole.PRIMARY, QuantumPurpose.SELF_PAY, ByteArray(32) { 0x11 }, active.state.authority.primaryKey, request.digest)
                val message = BagOfCells(identity.wallet.external(request.submission(sig), false)).toByteArray()
                value.put("signature", encode(sig)).put("message", encode(message))
                if (phase != QuantumPaymentOutbox.Phase.SIGNED) value.put("approval", JSONObject()
                    .put("message_sha256", hex(QuantumPaymentOutbox.digest(message))).put("observed_balance", "3")
                    .put("estimated_fee", "1").put("estimated_debit", "2").put("estimated_balance", "1")
                    .put("quoted_at", deadline - 2).put("expires_at", deadline))
            }
            write(value)
            return checkNotNull(outbox.pending(identity)).also { operations += it.id }
        }
        fun write(value: JSONObject) {
            val fd = Os.open(journal.absolutePath, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_TRUNC or OsConstants.O_NOFOLLOW, 0x180)
            FileOutputStream(fd).use { it.write(value.toString().toByteArray(Charsets.UTF_8)); it.fd.sync() }
        }
        override fun close() {
            journal.delete()
            for (operation in operations) {
                File(journal.parentFile, ".retiring-$operation").deleteRecursively()
                File(journal.parentFile, ".retiring-stage-$operation").deleteRecursively()
                val history = File(journal.parentFile, ".retirement-history")
                File(history, "$operation.json").delete()
                File(history, "$operation.json.tmp").delete()
                if (history.list()?.isEmpty() == true) history.delete()
            }
            File(context.noBackupFilesDir, "quantum-proof-checkpoints/$id").deleteRecursively()
            File(context.noBackupFilesDir, "quantum-creation/$id.json").delete()
        }
    }

    @Test fun everyPhaseCanRetireOnlyAfterFreshProofAndDoesNotCreateANewPayment() {
        for (phase in QuantumPaymentOutbox.Phase.entries) Fixture().use { f ->
            val entry = f.entry(phase, f.shardTime - 1)
            val cap = runBlocking { f.reader().retirementExpiry(f.id, f.identity) }
            val completed = f.outbox.retire(cap, null) { f.now }
            assertEquals(entry.id, completed.operationId)
            assertEquals(QuantumAuthorizationRetirement.State.RELEASE_COMPLETED, completed.state)
            assertEquals(QuantumAuthorizationRetirement.Result.EXPIRED_UNVERIFIED, completed.result)
            assertNull(f.outbox.pending(f.identity))
            assertFalse(f.outbox.retirementStatus(f.identity).recovering)
            assertEquals(1, f.outbox.localHistory(f.identity).size)
            assertThrows("Old capability recreated/deleted a slot", IllegalStateException::class.java) {
                f.outbox.retire(cap, null) { f.now }
            }
            assertNull(f.outbox.pending(f.identity))
        }
    }
    @Test fun durableFaultWindowsKeepSlotBlockedAndResumeFromFullSnapshot() {
        val points = listOf(
            "snapshot.before_open", "snapshot.before_write", "snapshot.before_file_sync", "snapshot.after_file_sync",
            "decision.before_open", "decision.after_file_sync", "workspace.before_dir_sync", "workspace.after_dir_sync",
            "snapshot_reconfirm.before_file_sync", "snapshot_reconfirm.after_file_sync", "decision_reconfirm.before_file_sync",
            "decision_reconfirm.after_file_sync", "workspace_reconfirm.before_dir_sync", "workspace_reconfirm.after_dir_sync",
            "workspace.before_rename", "workspace.after_rename", "workspace_publish.before_dir_sync",
            "release_pending.before_open", "release_pending.before_write", "release_pending.before_file_sync",
            "release_pending.after_file_sync", "release_pending.before_rename", "release_pending.after_rename",
            "release_pending.before_dir_sync", "release_pending.after_dir_sync", "canonical.before_recheck",
            "canonical.before_unlink", "canonical.after_unlink", "canonical_delete.before_dir_sync",
            "canonical_delete.after_dir_sync", "release_completed.before_open", "release_completed.before_write",
            "release_completed.before_file_sync", "release_completed.after_file_sync", "release_completed.before_rename",
            "release_completed.after_rename", "release_completed.before_dir_sync", "release_completed.after_dir_sync",
            "release_completed.durable", "cleanup_entry.before_unlink", "cleanup_entry.after_unlink",
            "cleanup_entry.before_dir_sync", "cleanup_decision.after_unlink", "cleanup_decision.after_dir_sync",
            "cleanup.before_rmdir", "cleanup.after_rmdir", "cleanup_workspace.before_dir_sync",
        )
        for (point in points) Fixture().use { f ->
            val entry = f.entry(QuantumPaymentOutbox.Phase.UNKNOWN, f.shardTime - 1)
            val original = f.journal.readBytes()
            val cap = runBlocking { f.reader().retirementExpiry(f.id, f.identity) }
            var fired = false
            val failing = QuantumPaymentOutbox(f.context) { event -> if (event == point && !fired) { fired = true; throw java.io.IOException(point) } }
            assertThrows("Fault not injected: $point", java.io.IOException::class.java) { failing.retire(cap, null) { f.now } }
            assertTrue("Fault not reached: $point", fired)
            if (f.journal.exists()) assertArrayEquals("Canonical changed at $point", original, f.journal.readBytes())
            val restarted = QuantumPaymentOutbox(f.context)
            val state = restarted.retirementStatus(f.identity)
            if (state.recovering && !state.completedCleanupOnly) {
                assertThrows("Unfinished retirement did not block ordinary access at $point", IllegalStateException::class.java) { restarted.pending(f.identity) }
                val fresh = runBlocking { f.reader().retirementExpiry(f.id, f.identity) }
                assertEquals(entry.id, fresh.operationId)
                restarted.retire(fresh, null) { f.now }
            } else if (state.completedCleanupOnly) restarted.cleanupCompletedRetirement()
            assertNull("Old slot survived completed recovery at $point", restarted.pending(f.identity))
            assertEquals(QuantumAuthorizationRetirement.State.RELEASE_COMPLETED, restarted.localHistory(f.identity).single().state)
        }
    }
    @Test fun missingCanonicalRecoveryRequiresFreshProofAndOldOperationCannotDeleteNewOne(): Unit = Fixture().use { f ->
        val entry = f.entry(QuantumPaymentOutbox.Phase.UNKNOWN, f.shardTime - 1)
        val cap = runBlocking { f.reader().retirementExpiry(f.id, f.identity) }
        val failing = QuantumPaymentOutbox(f.context) { if (it == "canonical.after_unlink") throw java.io.IOException(it) }
        assertThrows(java.io.IOException::class.java) { failing.retire(cap, null) { f.now } }
        assertFalse(f.journal.exists())
        val snapshot = f.outbox.retirementSnapshot(f.identity)
        assertEquals(entry.id, snapshot.id)
        assertThrows("Stale recovery capability deleted a slot", IllegalStateException::class.java) {
            f.outbox.retire(cap, null) { f.now + 301 }
        }
        val fresh = runBlocking { f.reader().retirementExpiry(f.id, f.identity) }
        f.write(entry.json().put("id", UUID.randomUUID().toString()))
        val replacement = f.journal.readBytes()
        assertThrows("Old operation deleted the replacement", IllegalStateException::class.java) {
            f.outbox.retire(fresh, null) { f.now }
        }
        assertArrayEquals(replacement, f.journal.readBytes())
        f.journal.delete()
        val page = runBlocking { f.reader().retirementOutcome(f.id, f.identity) }
        assertNull(page.receipt)
        f.outbox.retire(runBlocking { f.reader().retirementExpiry(f.id, f.identity) }, null) { f.now }
        assertNull(f.outbox.pending(f.identity))
    }
    @Test fun partialCompletedCleanupNeverNeedsEntryOrDeletesNewCanonical(): Unit = Fixture().use { f ->
        val entry = f.entry(QuantumPaymentOutbox.Phase.SIGNING, f.shardTime - 1)
        val cap = runBlocking { f.reader().retirementExpiry(f.id, f.identity) }
        val failing = QuantumPaymentOutbox(f.context) { if (it == "cleanup_entry.after_unlink") throw java.io.IOException(it) }
        assertThrows(java.io.IOException::class.java) { failing.retire(cap, null) { f.now } }
        assertTrue(f.outbox.retirementStatus(f.identity).completedCleanupOnly)
        val replacement = entry.json().put("id", UUID.randomUUID().toString())
        f.write(replacement); val bytes = f.journal.readBytes()
        f.outbox.cleanupCompletedRetirement()
        assertArrayEquals("Completed cleanup deleted a new canonical", bytes, f.journal.readBytes())
        assertFalse(f.outbox.retirementStatus(f.identity).recovering)
    }
    @Test fun unpublishedPartialSnapshotRequiresFreshCanonicalBoundCapability(): Unit = Fixture().use { f ->
        val entry = f.entry(QuantumPaymentOutbox.Phase.SIGNING, f.shardTime - 1)
        val cap = runBlocking { f.reader().retirementExpiry(f.id, f.identity) }
        val interrupted = QuantumPaymentOutbox(f.context) { if (it == "snapshot.before_open") throw java.io.IOException(it) }
        assertThrows(java.io.IOException::class.java) { interrupted.retire(cap, null) { f.now } }
        val stage = File(f.journal.parentFile, ".retiring-stage-${entry.id}")
        val snapshot = File(stage, "entry.json")
        assertFalse(snapshot.exists())
        val bytes = f.journal.readBytes()
        val staleDuringRebuild = QuantumPaymentOutbox(f.context) { if (it == "snapshot_rebuild.before_recheck") f.now += 301 }
        assertThrows(IllegalStateException::class.java) { staleDuringRebuild.retire(cap, null) { f.now } }
        assertFalse("Stale capability reconstructed a missing retirement snapshot", snapshot.exists())
        assertArrayEquals(bytes, f.journal.readBytes())
        f.now -= 301
        val other = UUID.randomUUID()
        val wrong = File(stage.parentFile, ".retiring-stage-$other")
        Os.rename(stage.absolutePath, wrong.absolutePath)
        try {
            assertThrows("Unrelated stage obtained expiry authority", IllegalStateException::class.java) {
                runBlocking { f.reader().retirementExpiry(f.id, f.identity) }
            }
            assertArrayEquals(bytes, f.journal.readBytes())
        } finally { Os.rename(wrong.absolutePath, stage.absolutePath) }
        f.outbox.retire(runBlocking { f.reader().retirementExpiry(f.id, f.identity) }, null) { f.now }
        assertNull(f.outbox.pending(f.identity))
    }
    @Test fun retirementSnapshotOperationMustMatchWorkspaceBeforeExpiryCapabilityIsIssued(): Unit = Fixture().use { f ->
        val entry = f.entry(QuantumPaymentOutbox.Phase.SIGNING, f.shardTime - 1)
        val cap = runBlocking { f.reader().retirementExpiry(f.id, f.identity) }
        val interrupted = QuantumPaymentOutbox(f.context) {
            if (it == "decision.before_open") throw java.io.IOException(it)
        }
        assertThrows(java.io.IOException::class.java) { interrupted.retire(cap, null) { f.now } }
        val stage = File(f.journal.parentFile, ".retiring-stage-${entry.id}")
        val retained = File(stage, "entry.json")
        assertTrue("Retirement interruption did not retain a complete entry", retained.isFile)
        val canonical = f.journal.readBytes()
        assertArrayEquals(canonical, retained.readBytes())

        val other = UUID.randomUUID()
        val renamed = File(stage.parentFile, ".retiring-stage-$other")
        Os.rename(stage.absolutePath, renamed.absolutePath)
        try {
            assertThrows("Retirement snapshot operation mismatch was accepted", IllegalStateException::class.java) {
                runBlocking { f.reader().retirementExpiry(f.id, f.identity) }
            }
            assertArrayEquals("Mismatched workspace changed canonical authorization", canonical, f.journal.readBytes())
        } finally { Os.rename(renamed.absolutePath, stage.absolutePath) }

        f.outbox.retire(runBlocking { f.reader().retirementExpiry(f.id, f.identity) }, null) { f.now }
        assertNull(f.outbox.pending(f.identity))
    }
    @Test fun ambiguousDecisionAndUnknownPendingRemainBlockedInsteadOfBeingRepaired(): Unit = Fixture().use { f ->
        val entry = f.entry(QuantumPaymentOutbox.Phase.SIGNING, f.shardTime - 1)
        val cap = runBlocking { f.reader().retirementExpiry(f.id, f.identity) }
        val interrupted = QuantumPaymentOutbox(f.context) { if (it == "decision.before_write") throw java.io.IOException(it) }
        assertThrows(java.io.IOException::class.java) { interrupted.retire(cap, null) { f.now } }
        val canonical = f.journal.readBytes()
        val decision = File(f.journal.parentFile, ".retiring-stage-${entry.id}/decision.json")
        assertEquals(0L, decision.length())
        assertThrows(IllegalStateException::class.java) { runBlocking { f.reader().retirementExpiry(f.id, f.identity) } }
        assertTrue(decision.exists()); assertArrayEquals(canonical, f.journal.readBytes())
        val pending = File(f.journal.parentFile, "unresolved.pending")
        val fd = Os.open(pending.absolutePath, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL, 0x180)
        FileOutputStream(fd).use { it.write(byteArrayOf(1)); it.fd.sync() }
        try {
            assertThrows("Unknown pending was bypassed during retirement", IllegalStateException::class.java) {
                f.outbox.retire(cap, null) { f.now }
            }
            assertTrue(pending.exists()); assertArrayEquals(canonical, f.journal.readBytes())
        } finally { pending.delete() }
    }
    @Test fun completedHistoryIsBoundedAndSafelyPrunesOldestInsteadOfLimitingLifetimePayments(): Unit = Fixture().use { f ->
        val operations = mutableListOf<UUID>()
        repeat(65) {
            val entry = f.entry(QuantumPaymentOutbox.Phase.SIGNING, f.shardTime - 1)
            operations += entry.id
            val cap = runBlocking { f.reader().retirementExpiry(f.id, f.identity) }
            f.outbox.retire(cap, null) { f.now }
            assertNull(f.outbox.pending(f.identity))
            f.now++
        }
        val history = f.outbox.localHistory(f.identity)
        assertEquals(64, history.size)
        assertFalse("Oldest completed record was not reclaimed", history.any { it.operationId == operations.first() })
        assertTrue(history.any { it.operationId == operations.last() })
        val directory = File(f.journal.parentFile, ".retirement-history")
        assertEquals(64, directory.list()!!.size)
        assertTrue(directory.listFiles()!!.all { it.length() <= 65536 })
        val pending = directory.listFiles()!!.first()
        val value = JSONObject(pending.readText()).put("state", "RELEASE_PENDING").put("completed_at", JSONObject.NULL)
        val fd = Os.open(pending.absolutePath, OsConstants.O_WRONLY or OsConstants.O_TRUNC or OsConstants.O_NOFOLLOW, 0x180)
        FileOutputStream(fd).use { it.write(value.toString().toByteArray()); it.fd.sync() }
        assertThrows("Release-pending history was treated as reclaimable", IllegalStateException::class.java) {
            f.outbox.retirementStatus(f.identity)
        }
        assertEquals(64, directory.list()!!.size)
        assertTrue(pending.exists())
    }
    @Test fun pendingBlocksValidRetirementBeforeAnyWorkspaceOrSummaryWrite(): Unit = Fixture().use { f ->
        val entry = f.entry(QuantumPaymentOutbox.Phase.SIGNING, f.shardTime - 1)
        val cap = runBlocking { f.reader().retirementExpiry(f.id, f.identity) }
        val original = f.journal.readBytes()
        val pending = File(f.journal.parentFile, "unresolved.pending")
        val fd = Os.open(pending.absolutePath, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL, 0x180)
        FileOutputStream(fd).use { it.write(byteArrayOf(1)); it.fd.sync() }
        try {
            val error = assertThrows("Pending did not block a valid retirement", IllegalStateException::class.java) {
                f.outbox.retire(cap, null) { f.now }
            }
            assertEquals("Outbox write recovery required", error.message)
            assertFalse(File(f.journal.parentFile, ".retiring-stage-${entry.id}").exists())
            assertFalse(File(f.journal.parentFile, ".retirement-history/${entry.id}.json").exists())
            assertTrue(pending.exists()); assertArrayEquals(original, f.journal.readBytes())
        } finally { pending.delete() }
    }
    @Test fun pendingSummaryAndDirectorySyncStrictlyPrecedeCanonicalDeletionAndCompletedCleanup(): Unit = Fixture().use { f ->
        val entry = f.entry(QuantumPaymentOutbox.Phase.UNKNOWN, f.shardTime - 1)
        val cap = runBlocking { f.reader().retirementExpiry(f.id, f.identity) }
        val raw = f.journal.readBytes()
        var pendingSynced = false; var canonicalSynced = false; var completedSynced = false
        val summaryFile = File(f.journal.parentFile, ".retirement-history/${entry.id}.json")
        val observer = QuantumPaymentOutbox(f.context) { point ->
            when (point) {
                "release_pending.after_dir_sync" -> pendingSynced = true
                "canonical.before_unlink" -> {
                    assertTrue("Canonical deletion preceded durable pending summary", pendingSynced)
                    assertTrue("Canonical deletion lacked release-pending summary", summaryFile.exists())
                    assertEquals("RELEASE_PENDING", JSONObject(summaryFile.readText()).getString("state"))
                    assertArrayEquals("Snapshot changed original canonical file bytes", raw,
                        File(f.journal.parentFile, ".retiring-${entry.id}/entry.json").readBytes())
                }
                "canonical_delete.after_dir_sync" -> canonicalSynced = true
                "release_completed.before_open" -> assertTrue("Completed publication preceded canonical directory sync", canonicalSynced)
                "release_completed.after_dir_sync" -> completedSynced = true
                "cleanup_entry.before_unlink" -> assertTrue("Snapshot cleanup preceded completed directory sync", completedSynced)
            }
        }
        observer.retire(cap, null) { f.now }
        assertTrue(pendingSynced && canonicalSynced && completedSynced)
        assertNull(f.outbox.pending(f.identity))
    }
    @Test fun restartingAfterDecisionFileSyncMustResyncRetainedFilesAndWorkspaceBeforePublication(): Unit = Fixture().use { f ->
        val entry = f.entry(QuantumPaymentOutbox.Phase.UNKNOWN, f.shardTime - 1)
        val cap = runBlocking { f.reader().retirementExpiry(f.id, f.identity) }
        val interrupted = QuantumPaymentOutbox(f.context) { if (it == "decision.after_file_sync") throw java.io.IOException(it) }
        assertThrows(java.io.IOException::class.java) { interrupted.retire(cap, null) { f.now } }
        val original = f.journal.readBytes()
        val fresh = runBlocking { f.reader().retirementExpiry(f.id, f.identity) }
        var snapshotSynced = false; var decisionSynced = false
        val restarted = QuantumPaymentOutbox(f.context) { point -> when (point) {
            "snapshot_reconfirm.after_file_sync" -> snapshotSynced = true
            "decision_reconfirm.after_file_sync" -> decisionSynced = true
            "workspace_reconfirm.before_dir_sync" -> {
                assertTrue("Restart workspace sync preceded retained-file sync", snapshotSynced && decisionSynced)
                throw java.io.IOException("restart-workspace-sync-failure")
            }
        } }
        val error = assertThrows("Restart skipped mandatory workspace sync before publication", java.io.IOException::class.java) {
            restarted.retire(fresh, null) { f.now }
        }
        assertEquals("restart-workspace-sync-failure", error.message)
        assertArrayEquals("Workspace sync failure lost canonical", original, f.journal.readBytes())
        assertFalse(File(f.journal.parentFile, ".retirement-history/${entry.id}.json").exists())
        assertTrue(File(f.journal.parentFile, ".retiring-stage-${entry.id}").exists())
        f.outbox.retire(runBlocking { f.reader().retirementExpiry(f.id, f.identity) }, null) { f.now }
        assertNull(f.outbox.pending(f.identity))
    }
    @Test fun concurrentRetirementHasOneWinnerAndOldCapabilityCannotClearExplicitNewReservation(): Unit = Fixture().use { f ->
        val old = f.entry(QuantumPaymentOutbox.Phase.SIGNING, f.shardTime - 1)
        val cap = runBlocking { f.reader().retirementExpiry(f.id, f.identity) }
        val start = java.util.concurrent.CountDownLatch(1)
        val winners = java.util.concurrent.atomic.AtomicInteger()
        val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val threads = List(2) {
            Thread {
                start.await()
                try { QuantumPaymentOutbox(f.context).retire(cap, null) { f.now }; winners.incrementAndGet() }
                catch (error: Throwable) { failures += error }
            }.also { it.start() }
        }
        start.countDown(); threads.forEach { it.join(10000); assertFalse(it.isAlive) }
        assertEquals(1, winners.get()); assertEquals(1, failures.size)
        assertTrue(failures.single() is IllegalStateException)
        assertEquals(old.id, f.outbox.localHistory(f.identity).single().operationId)
        val payment = QuantumPreparedPayment(f.observation, old.intent, f.now, 120)
        val replacement = f.outbox.reserve(payment, f.now)
        f.operations += replacement.id
        val bytes = f.journal.readBytes()
        assertNotEquals(old.id, replacement.id)
        assertThrows("Old expiry cleared an explicitly created new authorization", IllegalStateException::class.java) {
            f.outbox.retire(cap, null) { f.now }
        }
        assertArrayEquals(bytes, f.journal.readBytes())
    }
    private companion object {
        fun hex(value: ByteArray) = value.joinToString("") { "%02x".format(it.toInt() and 255) }
        fun encode(value: ByteArray) = Base64.encodeToString(value, Base64.NO_WRAP)
    }
}
