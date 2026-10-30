package network.tos.wallet.data.account

import android.system.Os
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import network.tos.blockchain.ton.contract.*
import network.tos.security.pq.QuantumProofSession
import network.tos.security.pq.QuantumProofTransport
import network.tos.wallet.data.account.pq.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class QuantumPaymentOutboxTest {
    @Test fun cancellationInsideCustodySigningKeepsDurableSlotAndNeverBroadcasts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fun bytes(name: String) = instrumentation.context.assets.open("quantum-self-pay-live/$name").use { it.readBytes() }
        val now = JSONObject(bytes("manifest.json").toString(Charsets.UTF_8)).getLong("controlled_now")
        val identity = TosQuantumSelfPayIdentity.fromBoc(Base64.decode(JSONObject(bytes("public-self-pay-accounts.json").toString(Charsets.UTF_8))
            .getJSONObject("accounts").getJSONObject("active").getString("identity_boc"), Base64.NO_WRAP))
        val anchor = JSONObject(bytes("anchor.json").toString(Charsets.UTF_8))
        val profile = QuantumNetworkProfile(1, anchor.getString("root_hash"), anchor.getString("file_hash"))
        val id = UUID.randomUUID()
        val birth = identity.cell.hash().toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) }
        val journal = File(context.noBackupFilesDir, "quantum-payment-outbox/$birth.json")
        check(!journal.exists()) { "Fixture birth identity already has an authorization" }
        val signerEntered = CountDownLatch(1)
        val signerRelease = CountDownLatch(1)
        val signerCalls = AtomicInteger()
        val broadcastCalls = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            QuantumCreationRecord(id, profile, identity).save(context)
            val policyNames = listOf("policy/material/masterchain-info.tl", "policy/material/chain-0000.tl", "policy/material/config.tl")
            fun transcript(names: List<String>): QuantumProofTransport {
                var index = 0
                return QuantumProofTransport { _, maximum -> bytes(names[index++]).also { check(it.size <= maximum) } }
            }
            QuantumProofSession(context, id, profile.anchor()).enrollBound(bytes("policy/request.json"), now,
                transcript(policyNames))
            val observation = QuantumAccountReader(context,
                transcript(policyNames + listOf("active/material/chain-0000.tl", "active/material/account.tl"))) { now }
                .observe(id, identity, intArrayOf(8, 19, 48))
            val payment = QuantumPreparedPayment(observation,
                QuantumPaymentIntent(identity.address, BigInteger.ONE, false, "cancel during custody"), now, 120)
            val coordinator = QuantumPaymentCoordinator(context,
                QuantumProofTransport { _, _ -> throw AssertionError("Cancelled signing reached simulation transport") },
                { broadcastCalls.incrementAndGet() },
                { _, _ ->
                    signerCalls.incrementAndGet(); signerEntered.countDown()
                    check(signerRelease.await(10, TimeUnit.SECONDS)) { "Cancellation did not interrupt the signing seam" }
                    ByteArray(0)
                }, { now })
            val authorization = scope.async { coordinator.authorizeAndEstimate(payment) }
            assertTrue("Coordinator did not reach protected-custody signing", signerEntered.await(10, TimeUnit.SECONDS))
            val beforeCancel = checkNotNull(QuantumPaymentOutbox(context).pending(identity))
            assertEquals(QuantumPaymentOutbox.Phase.SIGNING, beforeCancel.phase)
            assertNull(beforeCancel.signedBOC)
            val retainedBytes = journal.readBytes()

            runBlocking { authorization.cancelAndJoin() }
            assertTrue("Cancelled signing unexpectedly returned a quote", authorization.isCancelled)
            val afterCancel = checkNotNull(QuantumPaymentOutbox(context).pending(identity))
            assertEquals(beforeCancel.id, afterCancel.id)
            assertEquals(QuantumPaymentOutbox.Phase.SIGNING, afterCancel.phase)
            assertNull(afterCancel.signedBOC)
            assertArrayEquals("Cancellation changed the durable authorization", retainedBytes, journal.readBytes())
            assertEquals(1, signerCalls.get())
            assertEquals("Cancellation reached broadcast", 0, broadcastCalls.get())
            assertThrows(IllegalStateException::class.java) { QuantumPaymentOutbox(context).cancelUnsigned(afterCancel) }
            assertThrows(IllegalStateException::class.java) { QuantumPaymentOutbox(context).reserve(payment, now) }
        } finally {
            signerRelease.countDown(); scope.cancel()
            runBlocking { scope.coroutineContext[Job]?.join() }
            journal.delete()
            File(context.noBackupFilesDir, "quantum-proof-checkpoints/$id").deleteRecursively()
            File(context.noBackupFilesDir, "quantum-creation/$id.json").delete()
        }
    }

    @Test fun unsignedCancelAndSigningFailurePreserveSingleDurableAuthorization() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fun bytes(name: String) = instrumentation.context.assets.open("quantum-self-pay-live/$name").use { it.readBytes() }
        val now = JSONObject(bytes("manifest.json").toString(Charsets.UTF_8)).getLong("controlled_now")
        val identity = TosQuantumSelfPayIdentity.fromBoc(Base64.decode(JSONObject(bytes("public-self-pay-accounts.json").toString(Charsets.UTF_8))
            .getJSONObject("accounts").getJSONObject("active").getString("identity_boc"), Base64.NO_WRAP))
        val anchor = JSONObject(bytes("anchor.json").toString(Charsets.UTF_8))
        val profile = QuantumNetworkProfile(1, anchor.getString("root_hash"), anchor.getString("file_hash"))
        val id = UUID.randomUUID()
        val journal = File(context.noBackupFilesDir, "quantum-payment-outbox/" + identity.cell.hash().toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) } + ".json")
        check(!journal.exists()) { "Fixture birth identity already has an authorization" }
        try {
            QuantumCreationRecord(id, profile, identity).save(context)
            val policyNames = listOf("policy/material/masterchain-info.tl", "policy/material/chain-0000.tl", "policy/material/config.tl")
            fun transcript(names: List<String>): QuantumProofTransport {
                var index = 0
                return QuantumProofTransport { _, maximum -> bytes(names[index++]).also { check(it.size <= maximum) } }
            }
            QuantumProofSession(context, id, profile.anchor()).enrollBound(bytes("policy/request.json"), now, transcript(policyNames))
            val observation = QuantumAccountReader(context, transcript(policyNames + listOf("active/material/chain-0000.tl", "active/material/account.tl"))) { now }
                .observe(id, identity, intArrayOf(8, 19, 48))
            val intent = QuantumPaymentIntent(identity.address, BigInteger.ONE, false, "durable test")
            val payment = QuantumPreparedPayment(observation, intent, now, 120)
            val outbox = QuantumPaymentOutbox(context)
            val interruptInitialWrite = QuantumPaymentOutbox(context) { point ->
                if (point == "outbox_before_file_sync") throw IOException("Injected stop before outbox file sync")
            }
            assertThrows(IOException::class.java) { interruptInitialWrite.reserve(payment, now) }
            assertEquals(QuantumPaymentOutbox.PendingRecoveryKind.VALID_WITHOUT_CANONICAL,
                outbox.inspectPendingWriteRecovery().kind)
            val first = checkNotNull(outbox.pending(identity))
            assertFalse("Interrupted initial write was not promoted", journal.parentFile!!.listFiles()!!.any { it.extension == "pending" })
            assertEquals(first.id, QuantumPaymentOutbox(context).pending(identity)!!.id)
            assertThrows(IllegalStateException::class.java) { outbox.reserve(payment, now) }
            val interruptedUpdate = File(journal.parentFile, "${UUID.randomUUID()}.pending")
            val nextPhase = JSONObject(journal.readText())
            interruptedUpdate.writeText(nextPhase.toString())
            Os.chmod(interruptedUpdate.absolutePath, 0x180)
            val canonicalBeforeInspection = journal.readBytes()
            val pendingBeforeInspection = interruptedUpdate.readBytes()
            try {
                val inspection = outbox.inspectPendingWriteRecovery()
                assertEquals(QuantumPaymentOutbox.PendingRecoveryKind.MATCHING_SUCCESSOR, inspection.kind)
                assertEquals(QuantumPaymentOutbox.Phase.RESERVED, inspection.pendingPhase)
                assertEquals(QuantumPaymentOutbox.Phase.RESERVED, inspection.canonicalPhase)
                assertEquals(QuantumPaymentOutbox.Phase.RESERVED, outbox.pending(identity)!!.phase)
                assertFalse("Validated pending candidate was not promoted", interruptedUpdate.exists())
                assertArrayEquals(canonicalBeforeInspection, journal.readBytes())
                assertArrayEquals(pendingBeforeInspection, canonicalBeforeInspection)
            } finally { interruptedUpdate.delete() }
            val original = journal.readBytes()
            try {
                val malformed = JSONObject(original.toString(Charsets.UTF_8))
                malformed.getJSONObject("intent").put("amount", 1.0)
                journal.writeText(malformed.toString())
                assertThrows(IllegalStateException::class.java) { outbox.pending(identity) }
            } finally { journal.writeBytes(original) }
            assertEquals(first.id, outbox.pending(identity)!!.id)
            outbox.cancelUnsigned(first)
            assertNull(outbox.pending(identity))
            val interruptAfterRename = QuantumPaymentOutbox(context) { point ->
                if (point == "outbox_after_rename") throw IOException("Injected stop after outbox rename")
            }
            assertThrows(IOException::class.java) { interruptAfterRename.reserve(payment, now) }
            val transitioning = checkNotNull(outbox.pending(identity))
            assertEquals(QuantumPaymentOutbox.Phase.RESERVED, transitioning.phase)
            assertEquals(QuantumPaymentOutbox.PendingRecoveryKind.NONE, outbox.inspectPendingWriteRecovery().kind)
            val forwardCandidate = File(journal.parentFile, "${UUID.randomUUID()}.pending")
            forwardCandidate.writeText(JSONObject(journal.readText()).put("phase", QuantumPaymentOutbox.Phase.SIGNING.name).toString())
            Os.chmod(forwardCandidate.absolutePath, 0x180)
            assertEquals(QuantumPaymentOutbox.PendingRecoveryKind.MATCHING_SUCCESSOR,
                outbox.inspectPendingWriteRecovery().kind)
            assertEquals(QuantumPaymentOutbox.Phase.SIGNING, outbox.pending(identity)!!.phase)
            assertFalse("Recovery left the older RESERVED candidate behind", forwardCandidate.exists())
            assertThrows(IllegalStateException::class.java) { outbox.cancelUnsigned(transitioning) }
            assertTrue("Test fixture could not remove the deliberately stranded SIGNING entry", journal.delete())
            val directoryFd = Os.open(journal.parentFile!!.absolutePath, android.system.OsConstants.O_RDONLY, 0)
            try { Os.fsync(directoryFd) } finally { Os.close(directoryFd) }
            val start = java.util.concurrent.CountDownLatch(1)
            val workers = java.util.concurrent.Executors.newFixedThreadPool(2)
            try {
                val attempts = (0..1).map {
                    workers.submit<Pair<QuantumPaymentOutbox.Entry?, Throwable?>> {
                        check(start.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        try { Pair(outbox.reserve(payment, now), null) }
                        catch (error: Throwable) { Pair(null, error) }
                    }
                }
                start.countDown()
                val results = attempts.map { it.get(5, java.util.concurrent.TimeUnit.SECONDS) }
                assertEquals(1, results.count { it.first != null })
                assertEquals(1, results.count { it.second is IllegalStateException })
                outbox.cancelUnsigned(results.single { it.first != null }.first!!)
            } finally { start.countDown(); workers.shutdownNow() }
            val second = outbox.reserve(payment, now)
            assertThrows(IOException::class.java) {
                outbox.sign(second) {
                    assertEquals(QuantumPaymentOutbox.Phase.SIGNING, QuantumPaymentOutbox(context).pending(identity)!!.phase)
                    throw IOException("Interrupted custody lookup")
                }
            }
            val retained = QuantumPaymentOutbox(context).pending(identity)!!
            assertEquals(second.id, retained.id); assertEquals(QuantumPaymentOutbox.Phase.SIGNING, retained.phase)
            assertNull(retained.signedBOC)
            assertThrows(IllegalStateException::class.java) { outbox.cancelUnsigned(retained) }
            assertThrows(IllegalStateException::class.java) { outbox.reserve(payment, now) }
            val retainedBytes = journal.readBytes()
            val regressive = File(journal.parentFile, "${UUID.randomUUID()}.pending")
            try {
                regressive.writeText(JSONObject(retainedBytes.toString(Charsets.UTF_8))
                    .put("phase", QuantumPaymentOutbox.Phase.RESERVED.name).toString())
                Os.chmod(regressive.absolutePath, 0x180)
                assertEquals(QuantumPaymentOutbox.PendingRecoveryKind.CONFLICTING_CANONICAL,
                    outbox.reconcilePendingWriteRecovery().kind)
                assertTrue("Older RESERVED state replaced newer SIGNING state", regressive.isFile)
                assertArrayEquals(retainedBytes, journal.readBytes())
                assertThrows(IllegalStateException::class.java) { outbox.pending(identity) }
            } finally { regressive.delete() }
            val conflicting = File(journal.parentFile, "${UUID.randomUUID()}.pending")
            try {
                conflicting.writeText(JSONObject(retainedBytes.toString(Charsets.UTF_8))
                    .put("id", UUID.randomUUID().toString()).toString())
                Os.chmod(conflicting.absolutePath, 0x180)
                assertEquals(QuantumPaymentOutbox.PendingRecoveryKind.CONFLICTING_CANONICAL,
                    outbox.reconcilePendingWriteRecovery().kind)
                assertEquals(QuantumPaymentOutbox.PendingRecoveryKind.CONFLICTING_CANONICAL,
                    outbox.inspectPendingWriteRecovery().kind)
                assertThrows(IllegalStateException::class.java) { outbox.pending(identity) }
                assertTrue("Conflicting candidate was removed", conflicting.isFile)
                assertArrayEquals(retainedBytes, journal.readBytes())
            } finally { conflicting.delete() }
            val orphan = File(journal.parentFile, "${UUID.randomUUID()}.pending")
            try {
                assertTrue(orphan.createNewFile())
                assertEquals(QuantumPaymentOutbox.PendingRecoveryKind.INVALID_CANDIDATE,
                    QuantumPaymentOutbox(context).inspectPendingWriteRecovery().kind)
                assertThrows(IllegalStateException::class.java) { QuantumPaymentOutbox(context).pending(identity) }
                assertTrue("Uncertain write must not be silently deleted", orphan.isFile)
                assertArrayEquals(retainedBytes, journal.readBytes())
                val secondOrphan = File(journal.parentFile, "${UUID.randomUUID()}.pending")
                try {
                    assertTrue(secondOrphan.createNewFile())
                    val ambiguous = QuantumPaymentOutbox(context).inspectPendingWriteRecovery()
                    assertEquals(QuantumPaymentOutbox.PendingRecoveryKind.MULTIPLE_CANDIDATES, ambiguous.kind)
                    assertEquals(2, ambiguous.candidateCount)
                    assertArrayEquals(retainedBytes, journal.readBytes())
                    assertTrue(orphan.isFile && secondOrphan.isFile)
                } finally { secondOrphan.delete() }
            } finally { orphan.delete() }
            assertEquals(second.id, outbox.pending(identity)!!.id)

            val replay = File(journal.parentFile, "${UUID.randomUUID()}.pending")
            replay.writeBytes(journal.readBytes())
            Os.chmod(replay.absolutePath, 0x180)
            val canonicalBytes = journal.readBytes()
            val detached = File(context.noBackupFilesDir, "outbox-recovery-canonical-$id")
            assertTrue("Could not isolate the canonical file for the orphan case", journal.renameTo(detached))
            try {
                assertEquals(QuantumPaymentOutbox.PendingRecoveryKind.VALID_WITHOUT_CANONICAL,
                    outbox.inspectPendingWriteRecovery().kind)
                assertArrayEquals(canonicalBytes, replay.readBytes())
                assertEquals(QuantumPaymentOutbox.PendingRecoveryKind.RECONCILED,
                    outbox.reconcilePendingWriteRecovery().kind)
                assertFalse("Orphan pending candidate was not promoted", replay.exists())
                assertArrayEquals(canonicalBytes, journal.readBytes())
            } finally {
                if (detached.exists()) {
                    if (journal.exists()) assertTrue("Could not remove the displaced test copy", detached.delete())
                    else assertTrue("Could not restore canonical file", detached.renameTo(journal))
                }
            }

            val beforeRenameFailure = File(journal.parentFile, "${UUID.randomUUID()}.pending")
            beforeRenameFailure.writeBytes(canonicalBytes); Os.chmod(beforeRenameFailure.absolutePath, 0x180)
            val failBeforeRename = QuantumPaymentOutbox(context) { point ->
                if (point == "pending_recovery_before_rename") throw IOException("Injected pre-rename stop")
            }
            assertThrows(IOException::class.java) { failBeforeRename.reconcilePendingWriteRecovery() }
            assertTrue(beforeRenameFailure.isFile)
            assertArrayEquals("Pre-rename interruption changed canonical bytes", canonicalBytes, journal.readBytes())
            assertEquals(QuantumPaymentOutbox.Phase.SIGNING, outbox.pending(identity)!!.phase)
            assertFalse(beforeRenameFailure.exists())
            assertArrayEquals(canonicalBytes, journal.readBytes())

            val afterRenameFailure = File(journal.parentFile, "${UUID.randomUUID()}.pending")
            afterRenameFailure.writeBytes(canonicalBytes); Os.chmod(afterRenameFailure.absolutePath, 0x180)
            val failAfterRename = QuantumPaymentOutbox(context) { point ->
                if (point == "pending_recovery_after_rename") throw IOException("Injected post-rename stop")
            }
            assertThrows(IOException::class.java) { failAfterRename.reconcilePendingWriteRecovery() }
            assertFalse("Atomic promotion left the source pending name", afterRenameFailure.exists())
            assertArrayEquals("Post-rename interruption lost the complete authorization", canonicalBytes, journal.readBytes())
            assertEquals(second.id, outbox.pending(identity)!!.id)
        } finally {
            journal.delete()
            File(context.noBackupFilesDir, "quantum-proof-checkpoints/$id").deleteRecursively()
            File(context.noBackupFilesDir, "quantum-creation/$id.json").delete()
        }
    }
}
