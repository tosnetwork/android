package network.tos.security.pq

import android.system.Os
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class V5R2FeeStateDeviceTest {
    @Test fun realJniReservationSurvivesCloseAndRestartWaitsNextSlot() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val directory = File(context.cacheDir, "v5r2-state-${UUID.randomUUID()}")
        check(directory.mkdir())
        Os.chmod(directory.path, 448) // 0700
        val network = ByteArray(32) { 1 }; val vault = ByteArray(32) { 2 }; val tree = ByteArray(32) { 3 }
        fun open(time: Long) = V5R2FeeState.open(directory, 42, network, vault, tree, 100, time)
        fun refused(status: Int, operation: () -> Unit) {
            try { operation(); fail("Operation unexpectedly succeeded") }
            catch (error: FeeStateException) { assertEquals(status, error.status) }
        }
        try {
            val session = open(100)
            try {
                refused(-2) { open(100).close() }
                refused(-2) { session.preview(100, 0) }
                assertEquals(4L, session.preview(3700, 0))
                refused(-2) { session.reserve(3700, 0, 5, ByteArray(32) { 9 }) }
                assertTrue(session.reserve(3700, 0, 4, ByteArray(32) { 9 }) != 0L)
                assertEquals(5L, session.preview(3700, 0))
            } finally { session.close() }
            assertEquals("Fee state session closed", runCatching { session.preview(7300, 0) }.exceptionOrNull()?.message)
            val restored = open(3700)
            try {
                refused(-2) { restored.preview(3700, 0) }
                assertEquals(8L, restored.preview(7300, 0))
            } finally { restored.close() }
        } finally { directory.deleteRecursively() }
    }
}
