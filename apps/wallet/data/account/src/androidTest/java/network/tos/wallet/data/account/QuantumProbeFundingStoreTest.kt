package network.tos.wallet.data.account

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import network.tos.wallet.data.account.pq.QuantumProbeFundingStore
import network.tos.wallet.data.account.pq.QuantumProbeFundingJournal
import network.tos.wallet.data.account.pq.QuantumProbeFundingApproval
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Storage boundaries only. Opaque public bytes do not simulate authenticated handoff. */
@RunWith(AndroidJUnit4::class)
class QuantumProbeFundingStoreTest {
    private val key = "ab".repeat(32)
    private fun isolated(body: (File) -> Unit) {
        val parent = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "funding-storage-${UUID.randomUUID()}").apply { check(mkdir()) }
        try { body(parent) } finally { parent.deleteRecursively() }
    }
    @Test fun restartRetainsExactBytesAndNeverReplacesReservation() = isolated { parent ->
        val original = ByteArray(1024) { it.toByte() }
        QuantumProbeFundingStore(parent).reserve(key, original)
        assertArrayEquals(original, QuantumProbeFundingStore(parent).pending(key))
        assertThrows(IllegalStateException::class.java) { QuantumProbeFundingStore(parent).reserve(key, byteArrayOf(9)) }
        assertArrayEquals(original, QuantumProbeFundingStore(parent).pending(key))
        assertNull(QuantumProbeFundingStore(parent).pending("cd".repeat(32)))
    }
    @Test fun interruptedWritesBlockReplacementAcrossRestart() {
        for (point in listOf("before_file_sync", "before_rename")) isolated { parent ->
            val faulty = QuantumProbeFundingStore(parent) { if (it == point) error("Injected $point") }
            assertThrows(IllegalStateException::class.java) { faulty.reserve(key, byteArrayOf(1)) }
            assertThrows(IllegalStateException::class.java) { QuantumProbeFundingStore(parent).pending(key) }
            assertThrows(IllegalStateException::class.java) { QuantumProbeFundingStore(parent).reserve(key, byteArrayOf(2)) }
        }
    }
    @Test fun uncertainDirectorySyncRetainsCanonicalAndBlocksReplacement() = isolated { parent ->
        val original = byteArrayOf(1, 2, 3)
        val faulty = QuantumProbeFundingStore(parent) { if (it == "before_directory_sync") error("Injected directory sync") }
        assertThrows(IllegalStateException::class.java) { faulty.reserve(key, original) }
        assertArrayEquals(original, QuantumProbeFundingStore(parent).pending(key))
        assertThrows(IllegalStateException::class.java) { QuantumProbeFundingStore(parent).reserve(key, byteArrayOf(2)) }
    }
    @Test fun rejectsNonprivateAndLinkedFiles() = isolated { parent ->
        val store = QuantumProbeFundingStore(parent); store.reserve(key, byteArrayOf(1))
        val file = File(parent, "quantum-probe-funding/$key.qpf")
        Os.chmod(file.path, 0x1a4)
        assertThrows(IllegalStateException::class.java) { store.pending(key) }
        Os.chmod(file.path, 0x180)
        val alias = File(parent, "linked-record")
        try {
            Os.link(file.path, alias.path)
            assertThrows(IllegalStateException::class.java) { store.pending(key) }
            check(alias.delete())
        } catch (error: ErrnoException) {
            assertTrue(error.errno == OsConstants.EACCES || error.errno == OsConstants.EPERM)
            assertFalse(alias.exists())
            assertArrayEquals(byteArrayOf(1), store.pending(key))
            println("Android denied hard-link creation: errno=${error.errno}; application nlink branch unexercised")
        }
        check(file.delete())
        Os.symlink(File(parent, "outside-record").path, file.path)
        assertThrows(ErrnoException::class.java) { store.pending(key) }
    }
    @Test fun rejectsOverlappingLeaseAndDoesNotWrite() = isolated { parent ->
        val store = QuantumProbeFundingStore(parent); assertNull(store.pending(key))
        FileOutputStream(File(parent, "quantum-probe-funding/.lock"), true).use { stream ->
            stream.channel.lock().use {
                assertThrows(IllegalStateException::class.java) { store.reserve(key, byteArrayOf(1)) }
            }
        }
        assertNull(store.pending(key))
    }
    @Test fun enforcesSlotAndByteBudgets() = isolated { parent ->
        val store = QuantumProbeFundingStore(parent)
        assertThrows(IllegalArgumentException::class.java) { store.reserve(key, ByteArray(0)) }
        assertThrows(IllegalArgumentException::class.java) { store.reserve(key, ByteArray(262145)) }
        for (index in 0 until 64) store.reserve(index.toString(16).padStart(64, '0'), byteArrayOf(index.toByte()))
        assertThrows(IllegalStateException::class.java) { store.reserve(key, byteArrayOf(1)) }
        val file = File(parent, "quantum-probe-funding/${"0".repeat(64)}.qpf")
        file.writeBytes(ByteArray(262145))
        assertThrows(IllegalStateException::class.java) { store.pending("0".repeat(64)) }
    }
    @Test fun rejectsPathTraversal() = isolated { parent ->
        assertThrows(IllegalArgumentException::class.java) { QuantumProbeFundingStore(parent).reserve("../outside", byteArrayOf(1)) }
        assertFalse(File(parent, "outside.qpf").exists())
    }
    @Test fun transitionRequiresExactExistingBytesAndCannotReplay() = isolated { parent ->
        val before = byteArrayOf(1); val after = byteArrayOf(2)
        val store = QuantumProbeFundingStore(parent)
        assertThrows(IllegalStateException::class.java) { store.transition(key, before, after) }
        assertNull(store.pending(key))
        store.reserve(key, before)
        assertThrows(IllegalStateException::class.java) { store.transition(key, byteArrayOf(9), after) }
        assertArrayEquals(before, store.pending(key))
        store.transition(key, before, after)
        assertArrayEquals(after, QuantumProbeFundingStore(parent).pending(key))
        assertThrows(IllegalStateException::class.java) { store.transition(key, before, byteArrayOf(3)) }
        assertArrayEquals(after, store.pending(key))
    }
    @Test fun concurrentTransitionsHaveOnlyOneWinner() = isolated { parent ->
        val before = byteArrayOf(1); QuantumProbeFundingStore(parent).reserve(key, before)
        val start = CountDownLatch(1); val executor = Executors.newFixedThreadPool(2)
        try {
            val results = listOf(2, 3).map { value -> executor.submit<Boolean> {
                check(start.await(5, TimeUnit.SECONDS))
                try { QuantumProbeFundingStore(parent).transition(key, before, byteArrayOf(value.toByte())); true }
                catch (_: IllegalStateException) { false }
            } }
            start.countDown()
            assertEquals(1, results.count { it.get(10, TimeUnit.SECONDS) })
            val stored = checkNotNull(QuantumProbeFundingStore(parent).pending(key))
            assertTrue(stored.contentEquals(byteArrayOf(2)) || stored.contentEquals(byteArrayOf(3)))
        } finally { executor.shutdownNow(); check(executor.awaitTermination(10, TimeUnit.SECONDS)) }
    }
    @Test fun interruptedTransitionsCannotSilentlyReturnToReserved() {
        for (point in listOf("before_file_sync", "before_rename", "before_directory_sync")) isolated { parent ->
            val before = byteArrayOf(1); val after = byteArrayOf(2)
            QuantumProbeFundingStore(parent).reserve(key, before)
            val faulty = QuantumProbeFundingStore(parent) { if (it == point) error("Injected $point") }
            assertThrows(IllegalStateException::class.java) { faulty.transition(key, before, after) }
            val reopened = QuantumProbeFundingStore(parent)
            if (point == "before_directory_sync") assertArrayEquals(after, reopened.pending(key))
            else assertThrows(IllegalStateException::class.java) { reopened.pending(key) }
            assertThrows(IllegalStateException::class.java) { reopened.transition(key, before, byteArrayOf(3)) }
        }
    }
    /** Valid phase envelopes with opaque public payloads; these do not simulate chain authentication. */
    private fun completionTransition(): Pair<ByteArray, ByteArray> {
        val id = UUID(1, 2)
        val initial = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { output ->
            output.writeInt(0x51504631); output.writeLong(id.mostSignificantBits); output.writeLong(id.leastSignificantBits)
            output.writeInt(1); output.writeByte(7)
        } }.toByteArray()
        val approval = QuantumProbeFundingApproval.record(id, ByteArray(32) { 1 }, ByteArray(32) { 2 },
            1000, 1020, BigInteger("100"), BigInteger("1000"), BigInteger("890"), BigInteger("20"))
        val unknown = QuantumProbeFundingJournal.Reservation.decode(initial).beginSigning()
            .finishSigning(ByteArray(7856) { 9 }).beginSubmission(approval).unknown()
        val summary = java.nio.ByteBuffer.allocate(88).put(ByteArray(32) { 3 }).put(ByteArray(32) { 4 })
            .putLong(1100).put(ByteArray(16)).array()
        return unknown.encode() to unknown.finishCompletion(summary).encode()
    }
    @Test fun completionPhaseSurvivesStorageRestartWithoutReplacementOrDispatch() = isolated { parent ->
        val (unknown, completed) = completionTransition()
        val store = QuantumProbeFundingStore(parent); store.reserve(key, unknown)
        store.transition(key, unknown, completed)
        val bytes = checkNotNull(QuantumProbeFundingStore(parent).pending(key))
        assertArrayEquals(completed, bytes)
        val restored = QuantumProbeFundingJournal.Reservation.decode(bytes)
        assertEquals(QuantumProbeFundingJournal.Phase.COMPLETED, restored.phase)
        assertThrows(IllegalStateException::class.java) { restored.beginSigning() }
        assertThrows(IllegalStateException::class.java) { restored.beginSubmission(ByteArray(0)) }
        assertThrows(IllegalStateException::class.java) { restored.unknown() }
        assertThrows(IllegalStateException::class.java) { store.reserve(key, unknown) }
        assertThrows(IllegalStateException::class.java) { store.transition(key, unknown, completed) }
        assertArrayEquals(completed, QuantumProbeFundingStore(parent).pending(key))
    }
    @Test fun completionWriteFaultsRetainUncertaintyOrCompletedPhaseAcrossRestart() {
        for (point in listOf("before_file_sync", "before_rename", "before_directory_sync")) isolated { parent ->
            val (unknown, completed) = completionTransition()
            QuantumProbeFundingStore(parent).reserve(key, unknown)
            val faulty = QuantumProbeFundingStore(parent) { if (it == point) error("Injected completion write fault") }
            assertThrows(IllegalStateException::class.java) { faulty.transition(key, unknown, completed) }
            val canonical = File(parent, "quantum-probe-funding/$key.qpf")
            val next = File(canonical.path + ".next")
            val restarted = QuantumProbeFundingStore(parent)
            if (point == "before_directory_sync") {
                assertFalse(next.exists())
                val restored = QuantumProbeFundingJournal.Reservation.decode(checkNotNull(restarted.pending(key)))
                assertEquals(QuantumProbeFundingJournal.Phase.COMPLETED, restored.phase)
                assertArrayEquals(completed, canonical.readBytes())
                assertThrows(IllegalStateException::class.java) { restored.beginSubmission(ByteArray(0)) }
            } else {
                assertTrue(next.exists())
                assertArrayEquals(unknown, canonical.readBytes())
                assertThrows(IllegalStateException::class.java) { restarted.pending(key) }
            }
            assertThrows(IllegalStateException::class.java) { restarted.reserve(key, unknown) }
            assertThrows(IllegalStateException::class.java) { restarted.transition(key, unknown, completed) }
        }
    }
}
