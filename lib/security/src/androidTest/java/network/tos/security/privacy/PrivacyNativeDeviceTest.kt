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

    @Test fun completeSummaryRequiresReadyAndNeverReturnsBatchOrSecrets() {
        val f = fixture("privacy-session.json")
        val replay = PrivacyNative.replay(f.getJSONObject("request").toString().toByteArray(Charsets.UTF_8))
        replay.use {
            val early = ByteArray(32) { 0x42 }
            val error = assertThrows(IllegalStateException::class.java) { replay.summaryAndWipeSeed(early) }
            assertEquals("Privacy complete summary failed", error.message)
            assertArrayEquals(ByteArray(32), early)
            val transactions = f.getJSONArray("transactions")
            for (i in 0 until transactions.length()) replay.push(android.util.Base64.decode(transactions.getString(i), android.util.Base64.NO_WRAP))
            replay.finish()
            val seed = ByteArray(32) { 0x42 }
            val summary = JSONObject(replay.summaryAndWipeSeed(seed).toString(Charsets.UTF_8))
            assertArrayEquals(ByteArray(32), seed)
            assertEquals(setOf("version", "total_outputs", "owned_outputs", "unique_unspent_notes",
                "duplicate_outputs", "reused_indices_count", "diagnostics_count", "unspent_balance", "scan_next_index"),
                summary.keys().asSequence().toSet())
            assertEquals("13", summary.getString("total_outputs"))
            assertTrue(summary.getString("unspent_balance").matches(Regex("0|[1-9][0-9]*")))
            val mainSeed = ByteArray(32) { 0x42 }
            val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                try { replay.summaryAndWipeSeed(mainSeed) } catch (e: Throwable) { failure.set(e) }
            }
            assertEquals("Privacy summary must run on a worker thread", failure.get()?.message)
            assertArrayEquals(ByteArray(32), mainSeed)
            replay.close()
            val closed = ByteArray(32) { 0x42 }
            assertThrows(IllegalStateException::class.java) { replay.summaryAndWipeSeed(closed) }
            assertArrayEquals(ByteArray(32), closed)
        }
    }

    @Test fun completedSummaryRechecksProofAndCancellationAfterNativeWork() {
        val f = fixture("privacy-session.json")
        val transactions = f.getJSONArray("transactions")
        var cursor = transactions.length() - 1
        PrivacyHistoryWalk.replay(f.getJSONObject("request").toString().toByteArray(Charsets.UTF_8),
            PrivacyHistoryWalk.Query { _, _, _ -> android.util.Base64.decode(transactions.getString(cursor--), android.util.Base64.NO_WRAP) },
            recheck = {}).use { completed ->
            var checks = 0
            val seed = ByteArray(32) { 0x42 }
            val refused = assertThrows(IllegalArgumentException::class.java) {
                completed.summaryAndWipeSeed(seed, recheck = { checks++; require(checks != 2) { "Summary final proof expired" } })
            }
            assertEquals("Summary final proof expired", refused.message); assertEquals(2, checks)
            assertArrayEquals(ByteArray(32), seed)
            var cancelled = false
            val cancelledSeed = ByteArray(32) { 0x42 }
            val error = assertThrows(IllegalStateException::class.java) {
                completed.summaryAndWipeSeed(cancelledSeed, recheck = { cancelled = true }, stopped = { cancelled })
            }
            assertEquals("Privacy summary cancelled", error.message)
            assertArrayEquals(ByteArray(32), cancelledSeed)
            var current = 0
            val restored = ByteArray(32) { 0x42 }
            val summary = JSONObject(completed.summaryAndWipeSeed(restored, recheck = { current++ }).toString(Charsets.UTF_8))
            assertEquals("13", summary.getString("total_outputs")); assertEquals(2, current)
            assertArrayEquals(ByteArray(32), restored)
        }
    }

    @Test fun historyWalkVerifiesThenReplaysAndOwnsItsArchive() {
        val f = fixture("privacy-session.json")
        val request = f.getJSONObject("request").toString().toByteArray(Charsets.UTF_8)
        val tx = f.getJSONArray("transactions")
        val bocs = (0 until tx.length()).map { android.util.Base64.decode(tx.getString(it), android.util.Base64.NO_WRAP) }
        val original = bocs.map { it.copyOf() }
        var cursor = bocs.lastIndex; var checks = 0; var previous: ULong? = null
        try {
            PrivacyHistoryWalk.replay(request, PrivacyHistoryWalk.Query { address, lt, hash ->
                assertEquals("0:" + f.getJSONObject("request").getString("account_id"), address)
                assertTrue(hash.matches(Regex("[0-9a-f]{64}")))
                previous?.let { assertTrue(lt.toULong() < it) }; previous = lt.toULong()
                if (cursor < bocs.lastIndex) bocs[cursor + 1][0] = (bocs[cursor + 1][0].toInt() xor 1).toByte()
                bocs[cursor--]
            }, recheck = { checks++ }).use { completed ->
                assertEquals(2, checks); assertEquals(-1, cursor)
                completed.transactions().zip(original).forEach { (actual, expected) -> assertArrayEquals(expected, actual) }
                val outside = completed.transactions(); outside[0][0] = (outside[0][0].toInt() xor 1).toByte()
                assertArrayEquals(original[0], completed.transactions()[0])
                assertEquals("13", JSONObject(String(completed.info(), Charsets.UTF_8)).getString("total_outputs"))
                completed.close()
                assertThrows(IllegalStateException::class.java) { completed.transactions() }
                assertThrows(IllegalStateException::class.java) { completed.info() }
            }
        } catch (failure: Exception) {
            throw AssertionError("Valid fetched history did not remain isolated", failure)
        }
    }
    @Test fun historyWalkRejectsBadRPCFinalRecheckCancellationAndDeadline() {
        val f = fixture("privacy-session.json")
        val request = f.getJSONObject("request").toString().toByteArray(Charsets.UTF_8)
        val tx = f.getJSONArray("transactions")
        val bocs = (0 until tx.length()).map { android.util.Base64.decode(tx.getString(it), android.util.Base64.NO_WRAP) }
        assertThrows(IllegalStateException::class.java) {
            PrivacyHistoryWalk.replay(request, PrivacyHistoryWalk.Query { _, _, _ -> bocs.first() }, recheck = {}).use { }
        }
        var checks = 0; var cursor = bocs.lastIndex
        val finalError = assertThrows(IllegalArgumentException::class.java) {
            PrivacyHistoryWalk.replay(request, PrivacyHistoryWalk.Query { _, _, _ -> bocs[cursor--] }, recheck = {
                checks++; require(checks != 2) { "Final proof recheck refused" }
            }).use { }
        }
        assertEquals("Final proof recheck refused", finalError.message); assertEquals(2, checks); assertEquals(-1, cursor)
        for (deadline in listOf(false, true)) {
            var cancelled = false; var clock = 0L; var queries = 0
            val error = assertThrows(IllegalStateException::class.java) {
                PrivacyHistoryWalk.replay(request, PrivacyHistoryWalk.Query { _, _, _ ->
                    queries++; if (deadline) clock = 601_000_000_000L else cancelled = true; bocs.last()
                }, stopped = { cancelled }, recheck = {}, nanoTime = { clock }).use { }
            }
            assertEquals("Privacy sync cancelled or expired", error.message); assertEquals(1, queries)
        }
    }

    @Test fun replaySessionRequiresFinishAndClosesFailedOwners() {
        val fixture = fixture("privacy-session.json")
        val request = fixture.getJSONObject("request").toString().toByteArray(Charsets.UTF_8)
        PrivacyNative.replay(request).use { replay ->
            assertThrows(IllegalStateException::class.java) { replay.info() }
            val early = ByteArray(32) { 0x42 }
            assertThrows(IllegalStateException::class.java) { replay.scanAndWipeSeed(early, 0, 1) }
            assertArrayEquals(ByteArray(32), early)
            val transactions = fixture.getJSONArray("transactions")
            for (i in 0 until transactions.length()) replay.push(android.util.Base64.decode(transactions.getString(i), android.util.Base64.NO_WRAP))
            replay.finish()
            val info = JSONObject(String(replay.info(), Charsets.UTF_8))
            assertEquals(fixture.getJSONObject("info").getString("total_outputs"), info.getString("total_outputs"))
            val seed = ByteArray(32) { 0x42 }
            val page = JSONObject(String(replay.scanAndWipeSeed(seed, 0, 1), Charsets.UTF_8))
            assertEquals("1", page.getString("output_count"))
            assertArrayEquals(ByteArray(32), seed)
            replay.close(); replay.close()
            assertThrows(IllegalStateException::class.java) { replay.info() }
        }
        PrivacyNative.replay(request).use { premature ->
            assertThrows(IllegalStateException::class.java) { premature.finish() }
            assertThrows(IllegalStateException::class.java) { premature.info() }
        }
        PrivacyNative.replay(request).use { invalid ->
            assertThrows(IllegalStateException::class.java) { invalid.push(byteArrayOf()) }
            assertThrows(IllegalStateException::class.java) { invalid.info() }
        }
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
