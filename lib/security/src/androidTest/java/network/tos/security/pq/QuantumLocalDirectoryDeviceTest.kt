package network.tos.security.pq

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class QuantumLocalDirectoryDeviceTest {
    private fun fixture(body: (File) -> Unit) {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir, "bounded-directory-${UUID.randomUUID()}")
        Os.mkdir(root.absolutePath, 0x1c0)
        try { body(root) } finally { root.deleteRecursively() }
    }
    @Test fun boundedEnumerationRejectsOverflowInsteadOfTruncating(): Unit = fixture { root ->
        assertTrue(QuantumLocalDirectory.names(root.absolutePath, 1).isEmpty())
        repeat(256) { File(root, "entry-$it").writeBytes(byteArrayOf()) }
        assertEquals(256, QuantumLocalDirectory.names(root.absolutePath, 256).size)
        assertThrows("Enumeration truncated to its requested bound", IllegalStateException::class.java) {
            QuantumLocalDirectory.names(root.absolutePath, 255)
        }
        File(root, "overflow").writeBytes(byteArrayOf())
        assertThrows("Enumeration accepted the 257th entry", IllegalStateException::class.java) {
            QuantumLocalDirectory.names(root.absolutePath, 256)
        }
    }
    @Test fun invalidBasenameAndSymlinkDirectoryFailClosed(): Unit = fixture { root ->
        val maximumName = "a".repeat(255)
        File(root, maximumName).writeBytes(byteArrayOf())
        assertEquals(listOf(maximumName), QuantumLocalDirectory.names(root.absolutePath, 1))
        File(root, maximumName).delete()
        File(root, "invalid name").writeBytes(byteArrayOf())
        assertThrows(IllegalStateException::class.java) { QuantumLocalDirectory.names(root.absolutePath, 256) }
        File(root, "invalid name").delete()
        val alias = File(root.parentFile, "bounded-alias-${UUID.randomUUID()}")
        Os.symlink(root.absolutePath, alias.absolutePath)
        try { assertThrows(IllegalStateException::class.java) { QuantumLocalDirectory.names(alias.absolutePath, 256) } }
        finally { alias.delete() }
        Os.chmod(root.absolutePath, 0x1ed)
        try { assertThrows(IllegalStateException::class.java) { QuantumLocalDirectory.names(root.absolutePath, 256) } }
        finally { Os.chmod(root.absolutePath, 0x1c0) }
    }
    @Test fun failedEnumerationsCloseEveryDirectoryDescriptor(): Unit = fixture { root ->
        // One warmup loads the JNI library before the descriptor baseline.
        QuantumLocalDirectory.names(root.absolutePath, 1)
        File(root, "a").writeBytes(byteArrayOf()); File(root, "b").writeBytes(byteArrayOf())
        val before = File("/proc/self/fd").list()!!.size
        repeat(256) {
            assertThrows(IllegalStateException::class.java) { QuantumLocalDirectory.names(root.absolutePath, 1) }
        }
        val after = File("/proc/self/fd").list()!!.size
        assertTrue("Directory descriptors leaked ($before -> $after)", after <= before + 2)
    }
}
