package network.tos.security.pq

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class QuantumSimulationDeviceTest {
    @Test fun persistedSimulationBindsExactBytesAndNeverInitializesLostCheckpoint() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fun bytes(name: String) = instrumentation.context.assets.open("quantum-self-pay-simulation/$name").use { it.readBytes() }
        val id = UUID.randomUUID()
        val directory = File(context.noBackupFilesDir, "quantum-proof-checkpoints/$id")
        val anchor = bytes("anchor.json"); val request = bytes("request.json"); val message = bytes("message.boc")
        val now = bytes("clock.txt").toString(Charsets.UTF_8).trim().toLong()
        val session = QuantumProofSession(context, id, anchor)
        var calls = 0
        val relay = QuantumProofTransport { query, maximum ->
            calls++
            val name = when (query.size) {
                4 -> "masterchain-info.tl"
                168 -> "chain-0000.tl"
                120 -> "account.tl"
                88 -> "exec-config.tl"
                else -> error("Unexpected proof request")
            }
            assertFalse(query.contentEquals(message))
            bytes("material/$name").also { assertTrue(it.size <= maximum) }
        }
        try {
            assertEquals(-7, assertThrows(QuantumSimulationNative.Refused::class.java) {
                session.simulate(request, now, message, relay)
            }.code)
            assertEquals(0, calls)
            session.enroll(request, now, intArrayOf(1, 2, 5), arrayOf(
                bytes("material/masterchain-info.tl"), bytes("material/chain-0000.tl"), bytes("material/account.tl")))
            val result = session.simulate(request, now, message, relay)
            result.requireBinding(anchor, request, message, now, 30)
            assertEquals("executed", JSONObject(result.encoded.toString(Charsets.UTF_8)).getString("status"))
            for (i in 0..2) {
                assertThrows(IllegalStateException::class.java) {
                    result.requireBinding(if (i == 0) anchor + 0 else anchor, if (i == 1) request + 0 else request,
                        if (i == 2) message + 0 else message, now, 30)
                }
            }
            assertThrows(IllegalArgumentException::class.java) { result.requireBinding(anchor, request, message, now + 30, 30) }
            val checkpoint = File(directory, "checkpoint.json"); val saved = checkpoint.readBytes()
            assertThrows(IllegalStateException::class.java) {
                session.simulate(request, now, message, QuantumProofTransport { _, _ -> error("Original relay failure") })
            }.also { assertEquals("Original relay failure", it.message) }
            assertArrayEquals(saved, checkpoint.readBytes())
            assertTrue(checkpoint.delete()); val before = calls
            assertEquals(-7, assertThrows(QuantumSimulationNative.Refused::class.java) {
                session.simulate(request, now, message, relay)
            }.code)
            assertEquals(before, calls); assertFalse(checkpoint.exists())
        } finally { directory.deleteRecursively() }
    }
}
