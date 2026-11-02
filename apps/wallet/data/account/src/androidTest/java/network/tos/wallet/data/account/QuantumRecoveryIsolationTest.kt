package network.tos.wallet.data.account

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import network.tos.blockchain.ton.contract.*
import network.tos.wallet.data.account.pq.QuantumPaymentOutbox
import java.io.File
import java.io.IOException
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuantumRecoveryIsolationTest {
    @Test fun birthIsolationSurvivesReopenAndCorruptionFailsClosed() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(app.noBackupFilesDir, "guard-test-${UUID.randomUUID()}").also { check(it.mkdir()) }
        val context = object : ContextWrapper(app) { override fun getNoBackupFilesDir() = root }
        val identity = TosQuantumSelfPayIdentity(TosQuantumSelfPayWallet(
            QuantumSelfPayAuthority(-239, ByteArray(32) { 3 }, ByteArray(1312) { 7 }, ByteArray(32) { 8 })))
        try {
            val outbox = QuantumPaymentOutbox(context)
            assertFalse(outbox.requiresRecovery(identity))
            outbox.isolateRecoveredIdentity(identity)
            outbox.isolateRecoveredIdentity(identity)
            val alias = TosQuantumSelfPayIdentity.fromBoc(identity.toBoc())
            val reopened = QuantumPaymentOutbox(context)
            assertTrue(reopened.requiresRecovery(alias))
            assertNull(reopened.pending(alias))
            val marker = File(root, "quantum-payment-outbox").listFiles()!!.single { it.extension == "recovery" }
            marker.writeBytes(byteArrayOf(1))
            assertThrows(IllegalStateException::class.java) { reopened.requiresRecovery(alias) }
            assertThrows(IllegalStateException::class.java) { reopened.isolateRecoveredIdentity(alias) }
        } finally { check(root.deleteRecursively()) }
    }

    @Test fun interruptedGuardPublicationRetainsIsolationAndRetrySyncsIt() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(app.noBackupFilesDir, "guard-fault-${UUID.randomUUID()}").also { check(it.mkdir()) }
        val context = object : ContextWrapper(app) { override fun getNoBackupFilesDir() = root }
        val identity = TosQuantumSelfPayIdentity(TosQuantumSelfPayWallet(
            QuantumSelfPayAuthority(-239, ByteArray(32) { 3 }, ByteArray(1312) { 7 }, ByteArray(32) { 8 })))
        try {
            val outbox = QuantumPaymentOutbox(context) { point ->
                if (point == "recovery_guard_before_directory_sync") throw IOException("Injected directory sync interruption")
            }
            assertThrows(IOException::class.java) { outbox.isolateRecoveredIdentity(identity) }
            val reopened = QuantumPaymentOutbox(context)
            assertTrue(reopened.requiresRecovery(identity))
            reopened.isolateRecoveredIdentity(identity)
            assertTrue(QuantumPaymentOutbox(context).requiresRecovery(identity))
        } finally { check(root.deleteRecursively()) }
    }
}
