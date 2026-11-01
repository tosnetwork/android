package network.tos.wallet.data.account

import android.content.Context
import android.content.ContextWrapper
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import network.tos.wallet.data.account.pq.QuantumMigrationSigningAndroidIO
import network.tos.wallet.data.account.pq.QuantumMigrationSigningRecord
import network.tos.wallet.data.account.pq.QuantumMigrationSigningStore
import network.tos.wallet.data.account.pq.withOwnedOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real Android IO against isolated test-only directories and public synthetic records.
 * No wallet, native signature, dispatch or physical power-loss claim. */
@RunWith(AndroidJUnit4::class)
class QuantumMigrationSigningAndroidTest {
    private val hash = "1".repeat(64)
    private fun scope(body: (Context, File) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.noBackupFilesDir, "migration-io-test-${UUID.randomUUID()}")
        check(root.mkdir()); Os.chmod(root.absolutePath, 0x1c0)
        val isolated = object : ContextWrapper(base) { override fun getNoBackupFilesDir(): File = root }
        try { body(isolated, root) } finally { check(root.deleteRecursively()) }
    }
    private fun store(context: Context, key: String = hash) = QuantumMigrationSigningStore(QuantumMigrationSigningAndroidIO(context, key))
    private fun directory(root: File) = File(root, "quantum-migration-signing")

    @Test fun realFilesRetainOneSlotAcrossAdapterReloadAndSigningTransitions() = scope { context, root ->
        val first = store(context)
        assertNull(first.load())
        val reserved = first.reserve(byteArrayOf(1, 2, 3))
        val reloaded = checkNotNull(store(context).load())
        assertArrayEquals(reserved.encode(), reloaded.encode())
        assertThrows(IllegalStateException::class.java) { store(context).reserve(reserved.binding) }
        val signing = store(context).beginSigning(reloaded)
        assertThrows(IllegalStateException::class.java) { first.beginSigning(reserved) }
        val signed = first.finishSigning(signing, ByteArray(7856) { 7 })
        assertArrayEquals(signed.encode(), checkNotNull(store(context).load()).encode())
        assertThrows(IllegalStateException::class.java) { first.finishSigning(signing, ByteArray(7856) { 8 }) }
        val canonical = File(directory(root), "$hash.record")
        assertArrayEquals(signed.encode(), canonical.readBytes())
        assertFalse(File(directory(root), "$hash.next").exists())
        val stat = Os.lstat(canonical.absolutePath)
        assertEquals(0, stat.st_mode and 0x3f); assertEquals(Os.getuid(), stat.st_uid); assertEquals(1L, stat.st_nlink)
        assertEquals(0, Os.lstat(directory(root).absolutePath).st_mode and 0x3f)
    }

    @Test fun partialNextFileBlocksReloadAndDoesNotReplaceOriginalRecord() = scope { context, root ->
        val reserved = store(context).reserve(byteArrayOf(1))
        val pending = File(directory(root), "$hash.next")
        pending.writeBytes(ByteArray(0)); Os.chmod(pending.absolutePath, 0x180)
        assertThrows(IllegalStateException::class.java) { store(context).load() }
        assertThrows(IllegalStateException::class.java) { store(context).reserve(byteArrayOf(2)) }
        assertThrows(IllegalStateException::class.java) { store(context).beginSigning(reserved) }
        assertTrue(pending.isFile); assertEquals(0L, pending.length())
        assertArrayEquals(reserved.encode(), File(directory(root), "$hash.record").readBytes())
    }

    @Test fun heldFileLockRejectsSecondIoHandleUntilReleased() = scope { context, root ->
        val reserved = store(context).reserve(byteArrayOf(1))
        val fd = Os.open(File(directory(root), ".lock").absolutePath, OsConstants.O_RDWR or OsConstants.O_NOFOLLOW, 0)
        withOwnedOutputStream(fd) { stream ->
            val held = stream.channel.lock()
            try { assertThrows(IllegalStateException::class.java) { store(context).load() } }
            finally { held.release() }
        }
        assertArrayEquals(reserved.encode(), checkNotNull(store(context).load()).encode())
    }

    @Test fun linksCannotTouchProtectedTargets() {
        var symlinkRejected = 0
        var hardlinkRejected = 0
        var hardlinkDenied = 0
        for (hard in listOf(false, true)) for (name in listOf(".lock", "$hash.record", "$hash.next")) scope { context, root ->
            assertNull(store(context).load())
            val target = File(root, "protected-target").apply { writeBytes(byteArrayOf(7, 8, 9)) }
            Os.chmod(target.absolutePath, 0x180)
            val link = File(directory(root), name)
            if (link.exists()) check(link.delete())
            val created = try {
                if (hard) Os.link(target.absolutePath, link.absolutePath) else Os.symlink(target.absolutePath, link.absolutePath)
                true
            } catch (error: ErrnoException) {
                if (!hard || error.errno !in listOf(OsConstants.EACCES, OsConstants.EPERM)) throw error
                assertFalse(link.exists())
                assertEquals(1L, Os.lstat(target.absolutePath).st_nlink)
                hardlinkDenied++
                false
            }
            if (created) try {
                assertThrows(Exception::class.java) { store(context).load() }
                assertThrows(Exception::class.java) { store(context).reserve(byteArrayOf(1)) }
                if (hard) hardlinkRejected++ else symlinkRejected++
            } finally { Os.remove(link.absolutePath) }
            assertArrayEquals(byteArrayOf(7, 8, 9), target.readBytes())
        }
        assertEquals(3, symlinkRejected)
        assertEquals(3, hardlinkRejected + hardlinkDenied)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.cacheDir, "quantum-migration-link-controls-public.json").writeText(org.json.JSONObject()
            .put("symlink_adapter_rejected", symlinkRejected).put("hardlink_adapter_rejected", hardlinkRejected)
            .put("hardlink_creation_denied_by_platform", hardlinkDenied).put("targets_unchanged", true).toString())
    }

    @Test fun symlinkDirectoryAndBroadFilePermissionsAreRejected() {
        scope { context, root ->
            val protected = File(root, "protected-directory").apply { check(mkdir()) }
            val marker = File(protected, "marker").apply { writeBytes(byteArrayOf(7)) }
            val link = directory(root); Os.symlink(protected.absolutePath, link.absolutePath)
            try {
                assertThrows(IllegalStateException::class.java) { store(context).load() }
                assertEquals(listOf("marker"), protected.list()!!.toList())
                assertArrayEquals(byteArrayOf(7), marker.readBytes())
            } finally { Os.remove(link.absolutePath) }
        }
        scope { context, root ->
            val reserved = store(context).reserve(byteArrayOf(1))
            val canonical = File(directory(root), "$hash.record"); Os.chmod(canonical.absolutePath, 0x1a4)
            assertThrows(IllegalStateException::class.java) { store(context).load() }
            assertArrayEquals(reserved.encode(), canonical.readBytes())
        }
    }

    @Test fun capacityLimitRejectsSixtyFifthBirthBeforeCreatingTemporaryFile() = scope { context, root ->
        repeat(64) { index -> store(context, "%064x".format(index + 1)).reserve(byteArrayOf(index.toByte())) }
        val names = directory(root).list()!!.toSet()
        assertEquals(65, names.size)
        assertThrows(IllegalStateException::class.java) { store(context, "f".repeat(64)).reserve(byteArrayOf(1)) }
        assertEquals(names, directory(root).list()!!.toSet())
        assertFalse(directory(root).list()!!.any { it.endsWith(".next") })
        repeat(64) { index -> assertNotNull(store(context, "%064x".format(index + 1)).load()) }
    }

    @Test fun repeatedLoadsReleaseAllOwnedDescriptors() = scope { context, _ ->
        val reserved = store(context).reserve(byteArrayOf(1))
        repeat(4) { assertArrayEquals(reserved.encode(), checkNotNull(store(context).load()).encode()) }
        fun descriptors() = checkNotNull(File("/proc/self/fd").list()).size
        val before = descriptors()
        repeat(128) { assertArrayEquals(reserved.encode(), checkNotNull(store(context).load()).encode()) }
        val after = descriptors()
        assertTrue("Repeated loads must not retain owned descriptors: $before -> $after", after <= before + 3)
    }
}
