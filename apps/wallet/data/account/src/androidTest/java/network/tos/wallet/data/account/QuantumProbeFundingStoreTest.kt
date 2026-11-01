package network.tos.wallet.data.account

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import network.tos.wallet.data.account.pq.QuantumProbeFundingStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

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
}
