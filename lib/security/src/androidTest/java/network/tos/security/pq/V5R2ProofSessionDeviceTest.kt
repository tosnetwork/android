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
class V5R2ProofSessionDeviceTest {
    @Test fun noBackupSessionCommitsReopensAndRefusesLostState() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val assets = instrumentation.context.assets
        fun bytes(name: String) = assets.open("v5r2-proof/$name").use { it.readBytes() }
        val id = UUID.randomUUID()
        val directory = File(context.noBackupFilesDir, "v5r2-proof-checkpoints/$id")
        val anchor = bytes("anchor.json")
        val request = bytes("live-request.json")
        val fullNames = listOf("live/masterchain-info.tl", "live/config.tl", "historical/chain-0000.tl")
        val fullKinds = intArrayOf(1, 4, 2)
        val liveNames = listOf("live/masterchain-info.tl", "live/config.tl", "live/chain-0000.tl")
        val liveKinds = intArrayOf(1, 4, 2)
        try {
            val session = V5R2ProofSession(context, id, anchor)
            try { session.read(request, 1791200932, fullKinds, fullNames.map(::bytes).toTypedArray()); fail("Unenrolled session accepted") }
            catch (_: SecurityException) { }
            val result = session.enroll(request, 1791200932, fullKinds, fullNames.map(::bytes).toTypedArray())
            assertEquals("verified", JSONObject(result.toString(Charsets.UTF_8)).getString("status"))
            val checkpoint = File(directory, "checkpoint.json")
            assertTrue(checkpoint.isFile)
            assertEquals(636922, JSONObject(checkpoint.readText()).getJSONObject("head").getInt("seqno"))
            val reopened = V5R2ProofSession(context, id, anchor)
            assertTrue(reopened.read(request, 1791200932, liveKinds, liveNames.map(::bytes).toTypedArray()).isNotEmpty())
            assertTrue(checkpoint.delete())
            try { reopened.enroll(request, 1791200932, fullKinds, fullNames.map(::bytes).toTypedArray()); fail("Lost enrolled state reset") }
            catch (_: SecurityException) { }
        } finally {
            directory.deleteRecursively()
            File(context.filesDir, "v5r2-proof-checkpoints/$id").deleteRecursively()
        }
    }
    @Test fun callbackTransportAcquiresVerifiesAndPreservesStateOnFailure() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val assets = instrumentation.context.assets
        fun bytes(name: String) = assets.open("v5r2-proof/$name").use { it.readBytes() }
        val id = UUID.randomUUID()
        val directory = File(context.noBackupFilesDir, "v5r2-proof-checkpoints/$id")
        val session = V5R2ProofSession(context, id, bytes("anchor.json"))
        val request = bytes("live-request.json")
        val replies = listOf("live/masterchain-info.tl", "historical/chain-0000.tl", "live/config.tl").map(::bytes)
        var calls = 0
        val transport = V5R2ProofTransport { query, maximum ->
            assertTrue(query.isNotEmpty())
            check(calls < replies.size)
            replies[calls++].also { assertTrue(it.size <= maximum) }
        }
        try {
            val result = session.enrollBound(request, 1791200932, transport)
            result.requireLive(1791200932, 300)
            result.requireSameCheckpoint(result)
            assertTrue(result.provenConfigParam(34).isNotEmpty())
            try { result.provenConfigParam(48); fail("Unproven configuration accepted") }
            catch (_: IllegalStateException) { }
            try { result.requireLive(1791201932, 300); fail("Expired proven read accepted") }
            catch (_: IllegalStateException) { }
            try { result.requireLive(1791200931, 300); fail("Earlier local clock accepted") }
            catch (_: IllegalStateException) { }
            try { result.configParam(34, "00".repeat(32)); fail("Wrong configuration hash accepted") }
            catch (_: IllegalStateException) { }
            assertEquals(3, calls)
            val state = File(directory, "checkpoint.json")
            assertTrue(state.isFile)
            val before = state.readBytes()
            try {
                session.read(request, 1791200932, V5R2ProofTransport { _, _ -> throw IllegalStateException("Public test transport failure") })
                fail("Transport exception swallowed")
            } catch (error: IllegalStateException) { assertEquals("Public test transport failure", error.message) }
            assertArrayEquals(before, state.readBytes())
        } finally { directory.deleteRecursively() }
    }

}
