package network.tos.security.privacy

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivacyNativeDeviceTest {
    private fun fixture(name: String): JSONObject {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        return assets.open(name).bufferedReader().use { JSONObject(it.readText()) }
    }

    @Test fun actualRustDescriptorsIncludeTheFullUnsignedIndexAndWipeSeed() {
        val vectors = fixture("privacy-descriptors.json").getJSONArray("vectors")
        val domain = ByteArray(32).apply { this[31] = 7 }
        for (i in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(i)
            val seed = ByteArray(32) { 0x42 }
            val descriptor = PrivacyNative.descriptorAndWipeSeed(seed, domain, vector.getString("index").toULong())
            assertEquals(vector.getString("descriptor"), android.util.Base64.encodeToString(descriptor, android.util.Base64.NO_WRAP))
            assertArrayEquals(ByteArray(32), seed)
        }
    }

    @Test fun decryptsActualCiphertextAndRemovesSpentBalance() {
        val fixture = fixture("privacy-scan.json")
        val request = fixture.getJSONObject("request")
        var seed = ByteArray(32) { 0x42 }
        val first = JSONObject(String(PrivacyNative.scanAndWipeSeed(seed, request.toString().toByteArray(Charsets.UTF_8)), Charsets.UTF_8))
        assertEquals("1234567890", first.getString("batch_unspent_balance"))
        assertEquals("8", first.getString("next_note_key_index"))
        assertArrayEquals(ByteArray(32), seed)
        request.put("published_nullifiers", JSONArray().put(fixture.getString("nullifier")))
        seed = ByteArray(32) { 0x42 }
        val spent = JSONObject(String(PrivacyNative.scanAndWipeSeed(seed, request.toString().toByteArray(Charsets.UTF_8)), Charsets.UTF_8))
        assertEquals("0", spent.getString("batch_unspent_balance"))
        assertTrue(spent.getJSONArray("notes").getJSONObject(0).getBoolean("spent"))
        assertArrayEquals(ByteArray(32), seed)
    }

    @Test fun nativeFailureAndPreflightFailureBothWipeSeed() {
        val seed = ByteArray(32) { 0x42 }
        assertThrows(IllegalStateException::class.java) { PrivacyNative.scanAndWipeSeed(seed, "{}".toByteArray()) }
        assertArrayEquals(ByteArray(32), seed)
        val short = ByteArray(31) { 0x42 }
        assertThrows(IllegalArgumentException::class.java) { PrivacyNative.descriptorAndWipeSeed(short, ByteArray(32), 0uL) }
        assertArrayEquals(ByteArray(31), short)
    }
}
