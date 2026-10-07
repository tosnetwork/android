package network.tos.security.pq
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
@RunWith(AndroidJUnit4::class)
class V5R2ProofDeviceTest {
    @Test fun packagedKotlinJniVerifiesRealHistoricalProofAndRejectsMissingMaterial() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        fun read(name: String) = assets.open("v5r2-proof/$name").use { it.readBytes() }
        val names = assets.list("v5r2-proof/historical")!!.sorted()
        val tags = names.map { when {
            it.startsWith("chain-") -> 2
            it == "config.tl" -> 4
            it == "account.tl" -> 5
            it == "exec-config.tl" -> 6
            else -> error("Unexpected public fixture file")
        } }.toIntArray()
        val material = names.map { read("historical/$it") }.toTypedArray()
        val result = V5R2ProofNative.verify(read("anchor.json"), read("historical-request.json"), byteArrayOf(), 1791200932, tags, material)
        assertEquals("verified", JSONObject(result.verifiedJson.toString(Charsets.UTF_8)).getString("status"))
        assertTrue(result.nextState.isEmpty())
        try {
            V5R2ProofNative.verify(read("anchor.json"), read("historical-request.json"), byteArrayOf(), 1791200932, intArrayOf(), emptyArray())
            fail("Missing proof material accepted")
        } catch (_: SecurityException) { }
    }
}
