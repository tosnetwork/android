package network.tos.wallet.data.account

import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import network.tos.wallet.data.account.pq.QuantumProbeFundingStore
import network.tos.wallet.data.account.pq.QuantumPaymentOutbox
import network.tos.wallet.data.account.pq.withOwnedInputStream
import network.tos.wallet.data.account.pq.withOwnedOutputStream
import network.tos.blockchain.ton.contract.TosQuantumSelfPayIdentity
import network.tos.blockchain.ton.contract.TosQuantumSelfPayWallet
import network.tos.blockchain.ton.contract.QuantumSelfPayAuthority
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

/** Real OS descriptor lifetime tests; no native signer, wallet funds or RPC. */
@RunWith(AndroidJUnit4::class)
class QuantumDescriptorOwnershipTest {
    private fun isolated(body: (File) -> Unit) {
        val parent = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "descriptor-ownership-${UUID.randomUUID()}").apply { check(mkdir()) }
        try { body(parent) } finally { parent.deleteRecursively() }
    }
    private fun descriptorCount() = checkNotNull(File("/proc/self/fd").list()).size
    @Test fun platformDescriptorStreamsLeaveOwnershipWithCaller() = isolated { parent ->
        val fd = Os.open(File(parent, "public-byte").path, OsConstants.O_RDWR or OsConstants.O_CREAT or OsConstants.O_EXCL, 0x180)
        try {
            FileOutputStream(fd).use { it.write(7) }
            assertTrue(fd.valid()); Os.fstat(fd)
            Os.lseek(fd, 0, OsConstants.SEEK_SET)
            FileInputStream(fd).use { assertEquals(7, it.read()) }
            assertTrue(fd.valid()); Os.fstat(fd)
        } finally { if (fd.valid()) Os.close(fd) }
    }
    @Test fun repeatedFundingStatusReadsDoNotAccumulateDescriptors() = isolated { parent ->
        val store = QuantumProbeFundingStore(parent)
        val key = "ab".repeat(32)
        assertNull(store.pending(key))
        val before = descriptorCount()
        repeat(128) { assertNull(store.pending(key)) }
        val after = descriptorCount()
        println("funding_status_fd_before=$before after=$after")
        assertTrue("Status reads leaked descriptors: before=$before after=$after", after <= before + 4)
    }
    @Test fun ownedStreamsCloseOnSuccessAndFailureWithoutLosingTheOriginalError() = isolated { parent ->
        val file = File(parent, "public-byte")
        val output = Os.open(file.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL, 0x180)
        withOwnedOutputStream(output) { it.write(7); it.fd.sync() }
        assertFalse(output.valid())
        val input = Os.open(file.path, OsConstants.O_RDONLY, 0)
        val failure = IllegalStateException("Injected read failure")
        val caught = assertThrows(IllegalStateException::class.java) {
            withOwnedInputStream(input) { assertEquals(7, it.read()); throw failure }
        }
        assertSame(failure, caught); assertFalse(input.valid())
        val write = Os.open(file.path, OsConstants.O_WRONLY, 0)
        assertSame(failure, assertThrows(IllegalStateException::class.java) { withOwnedOutputStream(write) { throw failure } })
        assertFalse(write.valid())
    }
    @Test fun closedDescriptorCannotCloseAnUnrelatedReplacement() = isolated { parent ->
        val file = File(parent, "public-byte").apply { writeBytes(byteArrayOf(7)) }
        val old = Os.open(file.path, OsConstants.O_RDONLY, 0)
        withOwnedInputStream(old) { assertEquals(7, it.read()) }
        val replacement = Os.open(file.path, OsConstants.O_RDONLY, 0)
        try {
            assertThrows(Exception::class.java) { withOwnedInputStream(old) { it.read() } }
            assertTrue(replacement.valid()); Os.fstat(replacement)
        } finally { Os.close(replacement) }
    }
    @Test fun repeatedRecoveryIsolationReadsAndLeasesKeepDescriptorCountBounded() = isolated { parent ->
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = object : android.content.ContextWrapper(base) {
            override fun getNoBackupFilesDir() = parent
            override fun getApplicationContext(): android.content.Context = this
        }
        val identity = TosQuantumSelfPayIdentity(TosQuantumSelfPayWallet(QuantumSelfPayAuthority(
            1, ByteArray(32) { 0x42 }, ByteArray(1312), ByteArray(32))))
        val outbox = QuantumPaymentOutbox(context)
        outbox.isolateRecoveredIdentity(identity)
        assertTrue(outbox.requiresRecovery(identity))
        val before = descriptorCount()
        repeat(128) {
            assertTrue(outbox.requiresRecovery(identity))
            outbox.withRecoveryControlIsolation(identity) { }
        }
        val after = descriptorCount()
        println("recovery_isolation_fd_before=$before after=$after")
        assertTrue("Recovery reads leaked descriptors: before=$before after=$after", after <= before + 4)
    }
}
