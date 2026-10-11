package network.tos.security.pq
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
@RunWith(AndroidJUnit4::class)
class QuantumProofDeviceTest {
    @Test fun packagedKotlinJniVerifiesRealHistoricalProofAndRejectsMissingMaterial() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        fun read(name: String) = assets.open("quantum-proof/$name").use { it.readBytes() }
        val names = assets.list("quantum-proof/historical")!!.sorted()
        val tags = names.map { when {
            it.startsWith("chain-") -> 2
            it == "config.tl" -> 4
            it == "account.tl" -> 5
            it == "exec-config.tl" -> 6
            else -> error("Unexpected public fixture file")
        } }.toIntArray()
        val material = names.map { read("historical/$it") }.toTypedArray()
        val result = QuantumProofNative.verify(read("anchor.json"), read("historical-request.json"), byteArrayOf(), 1791200932, tags, material)
        assertEquals("verified", JSONObject(result.verifiedJson.toString(Charsets.UTF_8)).getString("status"))
        assertTrue(result.nextState.isEmpty())
        val bound = QuantumProofNative.verifyHistoricalBound(read("anchor.json"), read("historical-request.json"), byteArrayOf(), 1791200932, tags, material)
        val account = JSONObject(result.verifiedJson.toString(Charsets.UTF_8)).getJSONObject("account")
        val code = account.getString("code_hash")
        assertTrue(bound.accountState("-1:" + "33".repeat(32), code).isNotEmpty())
        try { bound.accountState("-1:" + "44".repeat(32), code); fail("Wrong proven account accepted") }
        catch (_: IllegalStateException) { }
        try { bound.accountState("-1:" + "33".repeat(32), "00".repeat(32)); fail("Wrong proven code accepted") }
        catch (_: IllegalStateException) { }
        try { bound.requireLive(1791200932, 300); fail("Historical read authorized live operation") }
        catch (_: IllegalStateException) { }
        bound.requireSameCheckpoint(bound)

        try {
            QuantumProofNative.verify(read("anchor.json"), read("historical-request.json"), byteArrayOf(), 1791200932, intArrayOf(), emptyArray())
            fail("Missing proof material accepted")
        } catch (_: SecurityException) { }
    }
}
