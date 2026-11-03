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

    @Test fun historyTransactionChecksEveryBinding() {
        val request = fixture("privacy-history.json").getJSONObject("request")
        fun hash(name: String) = request.getString(name).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val boc = android.util.Base64.decode(request.getString("boc"), android.util.Base64.NO_WRAP)
        val expected = hash("expected_hash"); val account = hash("account_id"); val state = hash("post_state_hash")
        val lt = request.getString("expected_lt").toULong(); val time = request.getString("maximum_time").toLong()
        val result = JSONObject(String(PrivacyNative.decodeHistoryTransaction(boc, expected, lt, account, state, time), Charsets.UTF_8))
        assertEquals(lt.toString(), result.getString("logical_time"))
        assertEquals("transact", result.getJSONObject("event").getString("kind"))
        for (i in 0..4) assertThrows(IllegalStateException::class.java) {
            PrivacyNative.decodeHistoryTransaction(boc, if (i == 0) ByteArray(32) { 1 } else expected,
                if (i == 1) ULong.MAX_VALUE else lt, if (i == 2) ByteArray(32) { 1 } else account,
                if (i == 3) ByteArray(32) { 1 } else state, if (i == 4) 0 else time)
        }
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

    @Test fun poolStateBindingAndFullWireSizeAreChecked() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val boc = assets.open("privacy-pool-state.boc").use { it.readBytes() }
        val expected = "50ba96d38f3e0e474737a1946973f7ba528775a246bbe9b8d02a97a0425d6b4f"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val decoded = JSONObject(String(PrivacyNative.decodePoolState(boc, expected), Charsets.UTF_8))
        assertEquals("20000000", decoded.getString("withdrawal_fee"))
        assertEquals("4294967295", decoded.getString("last_anchor_epoch"))
        assertThrows(IllegalStateException::class.java) { PrivacyNative.decodePoolState(boc, ByteArray(32)) }
        val width = boc[4].toInt() and 7
        val start = 6 + 3 * width
        val end = start + (boc[5].toInt() and 255)
        val total = boc.copyOfRange(start, end).fold(0L) { n, b -> (n shl 8) or (b.toLong() and 255) }
        val header = boc.copyOfRange(0, start).apply { this[5] = 8 }
        val changed = header + java.nio.ByteBuffer.allocate(8).putLong(total + (1L shl 32)).array() + boc.copyOfRange(end, boc.size)
        assertThrows(IllegalStateException::class.java) { PrivacyNative.decodePoolState(changed, expected) }
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
