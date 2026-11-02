package network.tos.wallet.data.account.pq

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Storage-protocol fault model, not Android syscalls or a physical power-loss experiment. */
class QuantumMigrationSigningStoreTest {
    private class State {
        var current: ByteArray? = null
        var pending: ByteArray? = null
        var fail: String? = null
        var failOccurrence = 1
        var writes = 0
    }
    private class MemoryIO(val state: State = State()) : QuantumMigrationSigningStore.IO {
        override fun <T> locked(body: () -> T): T = synchronized(state) { body() }
        private fun hit(point: String) {
            if (state.fail == point && --state.failOccurrence == 0) throw IOException("Injected storage failure")
        }
        override fun hasPending() = state.pending != null
        override fun readCurrent() = state.current?.copyOf()
        override fun writePending(bytes: ByteArray) {
            check(state.pending == null); state.pending = bytes.copyOf(); state.writes++
            hit("file_sync")
        }
        override fun replacePending() {
            hit("rename")
            state.current = checkNotNull(state.pending).copyOf(); state.pending = null
        }
        override fun syncCurrent() { hit("current_sync") }
        override fun syncDirectory() { hit("directory_sync") }
    }

    @Test fun signingDurabilityFailureNeverInvokesKey() {
        val io = MemoryIO(); val store = QuantumMigrationSigningStore(io)
        val reserved = store.reserve(byteArrayOf(1)); var calls = 0
        io.state.fail = "file_sync"
        assertThrows(IOException::class.java) {
            store.signOnce(reserved, { calls++; ByteArray(7856) }, {})
        }
        assertEquals(0, calls)
        assertThrows(IllegalStateException::class.java) { store.load() }
    }

    private fun approval(record: QuantumMigrationSigningRecord) = QuantumMigrationApproval.record(record.operation,
        ByteArray(32) { 3 }, ByteArray(32) { 4 }, 1000, 1030,
        java.math.BigInteger.valueOf(100), java.math.BigInteger.valueOf(90), java.math.BigInteger.TEN)

    @Test fun singleDispatchRetainsUnknownOnSuccessValidationFailureAndTransportFailure() {
        for (failure in listOf("none", "validation", "transport")) {
            val io = MemoryIO(); val store = QuantumMigrationSigningStore(io)
            val reserved = store.reserve(byteArrayOf(1)); val signed = store.signOnce(reserved, { ByteArray(7856) { 7 } }, {})
            var calls = 0
            val action = {
                store.submitOnce(signed, approval(signed), validate = {
                    assertEquals(QuantumMigrationSigningRecord.Phase.SUBMITTING, store.load()!!.phase)
                    if (failure == "validation") error("Injected validation failure")
                }, dispatch = {
                    calls++
                    assertEquals(QuantumMigrationSigningRecord.Phase.SUBMITTING, store.load()!!.phase)
                    if (failure == "transport") error("Injected transport failure")
                })
            }
            if (failure == "none") action() else assertThrows(IllegalStateException::class.java) { action() }
            assertEquals(if (failure == "validation") 0 else 1, calls)
            val restarted = QuantumMigrationSigningStore(MemoryIO(io.state))
            assertEquals(QuantumMigrationSigningRecord.Phase.UNKNOWN, restarted.load()!!.phase)
            assertThrows(IllegalStateException::class.java) {
                restarted.submitOnce(signed, approval(signed), {}, { calls++ })
            }
            assertEquals(if (failure == "validation") 0 else 1, calls)
        }
    }

    @Test fun failedSubmissionDurabilityNeverInvokesTransport() {
        val io = MemoryIO(); val store = QuantumMigrationSigningStore(io)
        val signed = store.signOnce(store.reserve(byteArrayOf(1)), { ByteArray(7856) { 7 } }, {})
        var calls = 0; io.state.fail = "file_sync"
        assertThrows(IOException::class.java) { store.submitOnce(signed, approval(signed), {}, { calls++ }) }
        assertEquals(0, calls)
        assertThrows(IllegalStateException::class.java) { store.load() }
    }

    @Test fun failedUncertaintyWriteAfterTransportNeverMakesSignatureReusable() {
        val io = MemoryIO(); val store = QuantumMigrationSigningStore(io)
        val signed = store.signOnce(store.reserve(byteArrayOf(1)), { ByteArray(7856) { 7 } }, {})
        var calls = 0
        assertThrows(IOException::class.java) {
            store.submitOnce(signed, approval(signed), {}, { calls++; io.state.fail = "file_sync" })
        }
        assertEquals(1, calls)
        assertEquals(QuantumMigrationSigningRecord.Phase.SUBMITTING,
            QuantumMigrationSigningRecord.decode(io.state.current!!).phase)
        val restarted = QuantumMigrationSigningStore(MemoryIO(io.state))
        assertThrows(IllegalStateException::class.java) { restarted.submitOnce(signed, approval(signed), {}, { calls++ }) }
        assertEquals(1, calls)
    }

    @Test fun submissionRetainsExactSignatureAndApprovalAcrossRestartAndCannotReopen() {
        val io = MemoryIO(); val store = QuantumMigrationSigningStore(io)
        val reserved = store.reserve(ByteArray(QuantumMigrationSigningRecord.MAX_BINDING_BYTES) { 1 })
        val signed = store.signOnce(reserved, { ByteArray(7856) { 7 } }, {})
        val approved = approval(signed)
        val submitting = store.beginSubmission(signed, approved)
        assertTrue(submitting.encode().size <= QuantumMigrationSigningRecord.MAX_FILE_BYTES)
        assertEquals(QuantumMigrationSigningRecord.Phase.SUBMITTING, store.load()!!.phase)
        approved.fill(0)
        assertArrayEquals(approval(signed), submitting.approval())
        assertArrayEquals(signed.signature(), submitting.signature())
        assertThrows(IllegalStateException::class.java) { store.beginSubmission(signed, approval(signed)) }
        val restarted = QuantumMigrationSigningStore(MemoryIO(io.state))
        val unknown = restarted.unknown(submitting)
        val decoded = QuantumMigrationSigningRecord.decode(unknown.encode())
        assertEquals(QuantumMigrationSigningRecord.Phase.UNKNOWN, decoded.phase)
        assertArrayEquals(signed.signature(), decoded.signature())
        assertArrayEquals(approval(signed), decoded.approval())
        assertThrows(IllegalStateException::class.java) { restarted.beginSubmission(decoded, approval(signed)) }
        assertThrows(IllegalStateException::class.java) { restarted.beginSigning(decoded) }
        assertThrows(IllegalStateException::class.java) { restarted.reserve(byteArrayOf(2)) }
        assertArrayEquals(decoded.encode(), restarted.load()!!.encode())
    }

    @Test fun failedSubmissionPublicationRemainsBlockedAndCannotUseAnotherApproval() {
        val io = MemoryIO(); val store = QuantumMigrationSigningStore(io)
        val reserved = store.reserve(byteArrayOf(1))
        val signed = store.signOnce(reserved, { ByteArray(7856) { 7 } }, {})
        val foreign = QuantumMigrationSigningRecord.reserve(byteArrayOf(1))
        assertThrows(IllegalArgumentException::class.java) { store.beginSubmission(signed, approval(foreign)) }
        assertArrayEquals(signed.encode(), store.load()!!.encode())
        io.state.fail = "directory_sync"; io.state.failOccurrence = 2 // First sync reads SIGNED; second publishes SUBMITTING.
        assertThrows(IOException::class.java) { store.beginSubmission(signed, approval(signed)) }
        val restarted = QuantumMigrationSigningStore(MemoryIO(io.state))
        assertEquals(QuantumMigrationSigningRecord.Phase.SUBMITTING, restarted.load()!!.phase)
        assertThrows(IllegalStateException::class.java) { restarted.beginSubmission(signed, approval(signed)) }
    }

    @Test fun failedSignerOrVerifierRetainsSigningAndNeverInvokesKeyAgain() {
        for (failSigner in listOf(false, true)) {
            val io = MemoryIO(); val store = QuantumMigrationSigningStore(io)
            val reserved = store.reserve(byteArrayOf(1)); var calls = 0
            val signature = ByteArray(7856) { 7 }
            assertThrows(IllegalStateException::class.java) {
                store.signOnce(reserved, {
                    calls++
                    assertEquals(QuantumMigrationSigningRecord.Phase.SIGNING, store.load()!!.phase)
                    if (failSigner) error("Injected signer failure")
                    signature
                }, { error("Injected verification failure") })
            }
            assertEquals(QuantumMigrationSigningRecord.Phase.SIGNING, store.load()!!.phase)
            if (!failSigner) assertTrue(signature.all { it == 0.toByte() })
            val restarted = QuantumMigrationSigningStore(MemoryIO(io.state))
            assertThrows(IllegalStateException::class.java) {
                restarted.signOnce(reserved, { calls++; ByteArray(7856) }, {})
            }
            assertEquals(1, calls)
        }
    }

    @Test fun successfulSigningVerifiesBeforePublicationAndWipesCallerBuffer() {
        val io = MemoryIO(); val store = QuantumMigrationSigningStore(io)
        val reserved = store.reserve(byteArrayOf(1)); val signature = ByteArray(7856) { 7 }
        var verified = false
        val signed = store.signOnce(reserved, { signature }, {
            assertEquals(QuantumMigrationSigningRecord.Phase.SIGNING, store.load()!!.phase)
            assertArrayEquals(ByteArray(7856) { 7 }, it)
            verified = true
        })
        assertTrue(verified)
        assertEquals(QuantumMigrationSigningRecord.Phase.SIGNED, signed.phase)
        assertArrayEquals(ByteArray(7856) { 7 }, store.load()!!.signature())
        assertTrue(signature.all { it == 0.toByte() })
    }

    @Test fun oneSlotCasSurvivesNewStoreAndRejectsDuplicateOrStaleUpdates() {
        val io = MemoryIO(); val store = QuantumMigrationSigningStore(io)
        assertNull(store.load())
        val reserved = store.reserve(byteArrayOf(1, 2, 3))
        assertThrows(IllegalStateException::class.java) { store.reserve(reserved.binding) }
        val foreign = QuantumMigrationSigningRecord.reserve(reserved.binding)
        assertThrows(IllegalStateException::class.java) { store.beginSigning(foreign) }
        val changed = reserved.encode().also { it[25] = 99 }
        assertThrows(IllegalStateException::class.java) { store.beginSigning(QuantumMigrationSigningRecord.decode(changed)) }
        val signing = QuantumMigrationSigningStore(MemoryIO(io.state)).beginSigning(reserved)
        assertThrows(IllegalStateException::class.java) { store.finishSigning(foreign.beginSigning(), ByteArray(7856)) }
        assertThrows(IllegalStateException::class.java) { store.beginSigning(reserved) }
        assertThrows(IllegalStateException::class.java) { store.beginSigning(signing) }
        val signed = store.finishSigning(signing, ByteArray(7856) { 7 })
        val retained = checkNotNull(QuantumMigrationSigningStore(MemoryIO(io.state)).load())
        assertArrayEquals(signed.encode(), retained.encode())
        assertThrows(IllegalStateException::class.java) { store.finishSigning(signing, ByteArray(7856) { 8 }) }
        assertThrows(IllegalStateException::class.java) { store.finishSigning(retained, ByteArray(7856) { 8 }) }
        assertEquals(3, io.state.writes)
    }

    @Test fun incompleteTemporaryRecordBlocksBothEmptyAndOccupiedSlot() {
        for (withCurrent in listOf(false, true)) {
            val io = MemoryIO(); val store = QuantumMigrationSigningStore(io)
            if (withCurrent) store.reserve(byteArrayOf(1))
            val before = io.state.current?.copyOf(); val writes = io.state.writes
            for (partial in listOf(ByteArray(0), byteArrayOf(1), ByteArray(30))) {
                io.state.pending = partial
                assertThrows(IllegalStateException::class.java) { store.load() }
                assertThrows(IllegalStateException::class.java) { store.reserve(byteArrayOf(2)) }
                assertArrayEquals(before, io.state.current)
                assertArrayEquals(partial, io.state.pending)
                assertEquals(writes, io.state.writes)
            }
        }
    }

    @Test fun fileSyncAndRenameFailureRetainTemporaryRecordAcrossReload() {
        for (point in listOf("file_sync", "rename")) {
            val io = MemoryIO(); val store = QuantumMigrationSigningStore(io)
            val reserved = store.reserve(byteArrayOf(1)); io.state.fail = point
            assertThrows(IOException::class.java) { store.beginSigning(reserved) }
            assertArrayEquals(reserved.encode(), io.state.current)
            assertNotNull(io.state.pending)
            io.state.fail = null
            assertThrows(IllegalStateException::class.java) { QuantumMigrationSigningStore(MemoryIO(io.state)).load() }
            assertThrows(IllegalStateException::class.java) { store.beginSigning(reserved) }
            assertEquals(2, io.state.writes)
        }
    }

    @Test fun postRenameDirectorySyncFailureRejectsTransitionAndRetainsSigningPhase() {
        val io = MemoryIO(); val store = QuantumMigrationSigningStore(io)
        val reserved = store.reserve(byteArrayOf(1))
        io.state.fail = "directory_sync"; io.state.failOccurrence = 2
        assertThrows(IOException::class.java) { store.beginSigning(reserved) }
        assertNull(io.state.pending)
        assertEquals(QuantumMigrationSigningRecord.Phase.SIGNING,
            QuantumMigrationSigningRecord.decode(checkNotNull(io.state.current)).phase)
        io.state.fail = null
        val reloaded = checkNotNull(QuantumMigrationSigningStore(MemoryIO(io.state)).load())
        assertThrows(IllegalStateException::class.java) { store.beginSigning(reloaded) }
        assertThrows(IllegalStateException::class.java) { store.beginSigning(reserved) }
        assertEquals(2, io.state.writes)
    }

    @Test fun retainedVisibleRecordMustRetryBothSyncsBeforeReturning() {
        for (point in listOf("current_sync", "directory_sync")) {
            val io = MemoryIO(); val store = QuantumMigrationSigningStore(io)
            val reserved = store.reserve(byteArrayOf(1))
            io.state.fail = point
            assertThrows(IOException::class.java) { QuantumMigrationSigningStore(MemoryIO(io.state)).load() }
            assertArrayEquals(reserved.encode(), io.state.current)
            io.state.fail = null
            assertArrayEquals(reserved.encode(), checkNotNull(store.load()).encode())
        }
    }

    @Test fun signedWriteFaultNeverReopensSigningOrReplacesSignature() {
        for (point in listOf("file_sync", "rename", "directory_sync")) {
            val io = MemoryIO(); val store = QuantumMigrationSigningStore(io)
            val signing = store.beginSigning(store.reserve(byteArrayOf(1)))
            val signature = ByteArray(7856) { 7 }
            io.state.fail = point; io.state.failOccurrence = if (point == "directory_sync") 2 else 1
            assertThrows(IOException::class.java) { store.finishSigning(signing, signature) }
            io.state.fail = null
            if (io.state.pending != null) {
                assertArrayEquals(signing.encode(), io.state.current)
                assertThrows(IllegalStateException::class.java) { store.load() }
            } else {
                val signed = checkNotNull(store.load())
                assertEquals(QuantumMigrationSigningRecord.Phase.SIGNED, signed.phase)
                assertArrayEquals(signature, signed.signature())
                assertThrows(IllegalStateException::class.java) { store.finishSigning(signed, ByteArray(7856) { 8 }) }
            }
            assertThrows(IllegalStateException::class.java) { store.beginSigning(signing) }
            assertThrows(IllegalStateException::class.java) { store.finishSigning(signing, ByteArray(7856) { 8 }) }
            assertEquals(3, io.state.writes)
        }
    }

    @Test fun concurrentReservationsHaveExactlyOneWinnerInSharedLockedBackend() {
        val state = State(); val start = CountDownLatch(1); val done = CountDownLatch(16)
        val successes = AtomicInteger(); val rejected = AtomicInteger(); val failures = AtomicInteger()
        val executor = Executors.newFixedThreadPool(4)
        try {
            repeat(16) { index -> executor.submit {
                try {
                    start.await()
                    QuantumMigrationSigningStore(MemoryIO(state)).reserve(byteArrayOf(index.toByte()))
                    successes.incrementAndGet()
                } catch (_: IllegalStateException) { rejected.incrementAndGet() }
                catch (_: Throwable) { failures.incrementAndGet() }
                finally { done.countDown() }
            } }
            start.countDown(); assertTrue(done.await(10, TimeUnit.SECONDS))
            assertEquals(1, successes.get()); assertEquals(15, rejected.get()); assertEquals(0, failures.get())
            assertEquals(1, state.writes)
        } finally { executor.shutdownNow() }
    }

    @Test fun oversizedOrMalformedRecordCannotConsumeOrReopenSlot() {
        val io = MemoryIO(); val store = QuantumMigrationSigningStore(io)
        assertThrows(IllegalArgumentException::class.java) {
            store.reserve(ByteArray(QuantumMigrationSigningRecord.MAX_BINDING_BYTES + 1))
        }
        assertNull(io.state.current); assertNull(io.state.pending); assertEquals(0, io.state.writes)
        for (malformed in listOf(ByteArray(0), ByteArray(30), ByteArray(QuantumMigrationSigningRecord.MAX_FILE_BYTES + 1))) {
            io.state.current = malformed
            assertThrows(Exception::class.java) { store.load() }
            assertThrows(Exception::class.java) { store.reserve(byteArrayOf(1)) }
            assertArrayEquals(malformed, io.state.current)
        }
        assertEquals(0, io.state.writes)
    }
}
