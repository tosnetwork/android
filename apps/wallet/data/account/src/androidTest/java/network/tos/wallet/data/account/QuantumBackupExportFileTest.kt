package network.tos.wallet.data.account

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.ErrnoException
import android.system.OsConstants
import network.tos.wallet.data.account.pq.QuantumBackupExportFile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.UUID

/** Transport/durability tests use synthetic ciphertext-shaped bytes, not secret wallet material. */
@RunWith(AndroidJUnit4::class)
class QuantumBackupExportFileTest {
    private fun scope(body: (File) -> Unit) {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir,
            "export-file-test-${UUID.randomUUID()}").also { check(it.mkdir()) }
        try { body(root) } finally { check(root.deleteRecursively()) }
    }
    private fun descriptor(file: File) = ParcelFileDescriptor.open(file,
        ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE)

    @Test fun privateDurableStageAndVerifiedDestination() = scope { root ->
        val directory = File(root, "private")
        val store = QuantumBackupExportFile(directory, mutableSetOf())
        val bytes = ByteArray(150) { it.toByte() }
        val ticket = store.stage(bytes)
        assertArrayEquals(bytes, store.read(ticket))
        val staged = directory.listFiles()!!.single()
        assertTrue(staged.name.endsWith(".ready"))
        assertEquals(0, Os.lstat(staged.path).st_mode and 0x3f)
        val destination = File(root, "selected-document")
        store.writeAndVerify(ticket, { descriptor(destination) }, { destination.inputStream() })
        assertArrayEquals(bytes, destination.readBytes())
        store.discard(ticket); store.discard(ticket)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun failedStageCannotPublishAndCanRetry() = scope { root ->
        for (point in listOf("before_file_sync", "before_directory_sync")) {
            val active = mutableSetOf<UUID>()
            val directory = File(root, point)
            val failing = QuantumBackupExportFile(directory, active) { if (it == point) throw IOException("Injected export persistence failure") }
            assertThrows(IOException::class.java) { failing.stage(ByteArray(58)) }
            assertTrue(active.isEmpty()); assertTrue(directory.listFiles()!!.isEmpty())
            val store = QuantumBackupExportFile(directory, active)
            val ticket = store.stage(ByteArray(58))
            assertEquals(58, store.read(ticket).size); store.discard(ticket)
        }
    }

    @Test fun capacityAndProcessRestartDiscardUnclaimedCiphertext() = scope { root ->
        val directory = File(root, "private")
        val active = mutableSetOf<UUID>()
        val first = QuantumBackupExportFile(directory, active)
        val a = first.stage(ByteArray(58)); first.stage(ByteArray(59))
        assertThrows(IllegalStateException::class.java) { first.stage(ByteArray(60)) }
        val sameProcess = QuantumBackupExportFile(directory, active)
        assertEquals(58, sameProcess.read(a).size)
        // Explicit fresh registry models a new process; production shares its bounded registry.
        val restarted = QuantumBackupExportFile(directory, mutableSetOf())
        val replacement = restarted.stage(ByteArray(61))
        assertEquals(1, directory.listFiles()!!.size)
        assertEquals(61, restarted.read(replacement).size)
        assertThrows(IllegalArgumentException::class.java) { restarted.read(a) }
        restarted.discard(replacement)
    }

    @Test fun localTamperAndLinksFailClosedWithoutTouchingTargets() = scope { root ->
        val directory = File(root, "private"); val store = QuantumBackupExportFile(directory, mutableSetOf())
        val bytes = ByteArray(58) { 7 }; val ticket = store.stage(bytes)
        val staged = directory.listFiles()!!.single()
        staged.writeBytes(bytes.copyOf().also { it[0] = 8 })
        assertThrows(IllegalStateException::class.java) { store.read(ticket) }
        staged.writeBytes(bytes)
        val alias = File(root, "hardlink")
        val evidence = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "export-link-controls.txt")
        val linked = try { Os.link(staged.path, alias.path); true }
        catch (error: ErrnoException) {
            if (error.errno !in listOf(OsConstants.EACCES, OsConstants.EPERM)) throw error
            assertFalse(alias.exists()); assertArrayEquals(bytes, store.read(ticket))
            evidence.appendText("hardlink_creation_denied_errno=${error.errno}\n"); false
        }
        if (linked) {
            assertThrows(IllegalStateException::class.java) { store.read(ticket) }
            evidence.appendText("hardlink_read_rejected\n"); Os.remove(alias.path)
        }
        val retained = File(root, "retained")
        Os.rename(staged.path, retained.path)
        val symlinked = try { Os.symlink(retained.path, staged.path); true }
        catch (error: ErrnoException) {
            if (error.errno !in listOf(OsConstants.EACCES, OsConstants.EPERM)) throw error
            assertFalse(staged.exists()); evidence.appendText("symlink_creation_denied_errno=${error.errno}\n"); false
        }
        if (symlinked) {
            assertThrows(IllegalStateException::class.java) { store.read(ticket) }
            evidence.appendText("symlink_read_rejected\n"); Os.remove(staged.path)
        }
        assertArrayEquals(bytes, retained.readBytes())
        Os.rename(retained.path, staged.path); assertArrayEquals(bytes, store.read(ticket)); store.discard(ticket)
    }

    @Test fun destinationFailuresDoNotConsumeStagedBackupAndExtraDataIsBounded() = scope { root ->
        val directory = File(root, "private"); val store = QuantumBackupExportFile(directory, mutableSetOf())
        val bytes = ByteArray(58) { 3 }; val ticket = store.stage(bytes); val destination = File(root, "output")
        assertThrows(IOException::class.java) {
            store.writeAndVerify(ticket, { throw IOException("Injected provider refusal") }, { fail("Must not read after write refusal"); ByteArrayInputStream(bytes) })
        }
        for (readBack in listOf(ByteArray(58), bytes + byteArrayOf(1), bytes.copyOf(57))) {
            assertThrows(IllegalStateException::class.java) {
                store.writeAndVerify(ticket, { descriptor(destination) }, { ByteArrayInputStream(readBack) })
            }
            assertArrayEquals(bytes, store.read(ticket))
        }
        var consumed = 0
        assertThrows(IllegalStateException::class.java) {
            store.writeAndVerify(ticket, { descriptor(destination) }, {
                object : java.io.InputStream() {
                    override fun read(): Int = 3
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (consumed + length > bytes.size + 1) throw IOException("Reader exceeded bounded verification")
                        consumed += length; buffer.fill(3, offset, offset + length); return length
                    }
                }
            })
        }
        assertEquals(bytes.size + 1, consumed)
        store.writeAndVerify(ticket, { descriptor(destination) }, {
            assertThrows(IllegalStateException::class.java) {
                store.writeAndVerify(ticket, { fail("Overlapping writer opened"); descriptor(destination) }, { ByteArrayInputStream(bytes) })
            }
            ByteArrayInputStream(bytes)
        })
        store.discard(ticket)
    }
}
