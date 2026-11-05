package network.tos.security.privacy

import org.junit.Assert.*
import org.junit.Test

class PrivacySummaryReadTest {
    @Test fun checksBothSidesAndOnlyPublishesCompleteResponse() {
        val seed = ByteArray(32) { 7 }; val response = "complete".toByteArray()
        val calls = arrayListOf<String>()
        val actual = PrivacySummaryRead.readAndWipeSeed(seed, { calls += "check" }) {
            calls += "native"; assertEquals(7, seed[0].toInt()); response
        }
        assertSame(response, actual)
        assertEquals(listOf("check", "native", "check"), calls)
        assertArrayEquals(ByteArray(32), seed)
        assertEquals("complete", actual.toString(Charsets.UTF_8))
    }

    @Test fun invalidSeedAndPreflightRefusalNeverReadAndAlwaysWipe() {
        val short = ByteArray(31) { 7 }
        assertThrows(IllegalArgumentException::class.java) {
            PrivacySummaryRead.readAndWipeSeed(short, {}) { error("must not read") }
        }
        assertArrayEquals(ByteArray(31), short)
        for (reason in listOf("closed", "main thread", "cancelled", "expired proof")) {
            val seed = ByteArray(32) { 7 }; var called = false
            val error = assertThrows(IllegalStateException::class.java) {
                PrivacySummaryRead.readAndWipeSeed(seed, { error(reason) }) { called = true; byteArrayOf(1) }
            }
            assertEquals(reason, error.message); assertFalse(called); assertArrayEquals(ByteArray(32), seed)
        }
    }

    @Test fun nativeFailureNeverFallsBackAndWipesSeed() {
        val seed = ByteArray(32) { 7 }; var reads = 0
        val error = assertThrows(UnsatisfiedLinkError::class.java) {
            PrivacySummaryRead.readAndWipeSeed(seed, {}) { reads++; throw UnsatisfiedLinkError("missing summary") }
        }
        assertEquals("missing summary", error.message); assertEquals(1, reads); assertArrayEquals(ByteArray(32), seed)
    }

    @Test fun finalCancellationOrExpiryRejectsAndClearsUnpublishedResponse() {
        for (reason in listOf("cancelled during native", "proof expired during native")) {
            val seed = ByteArray(32) { 7 }; val response = byteArrayOf(1, 2, 3); var checks = 0
            val error = assertThrows(IllegalStateException::class.java) {
                PrivacySummaryRead.readAndWipeSeed(seed, { checks++; check(checks != 2) { reason } }) { response }
            }
            assertEquals(reason, error.message); assertEquals(2, checks)
            assertArrayEquals(ByteArray(32), seed); assertArrayEquals(ByteArray(3), response)
        }
    }

    @Test fun emptyAndOversizeResponsesAreRejectedWithoutPublishing() {
        for (size in listOf(0, 1024 * 1024 + 1)) {
            val seed = ByteArray(32) { 7 }; val response = ByteArray(size) { 1 }; var checks = 0
            assertThrows(IllegalStateException::class.java) {
                PrivacySummaryRead.readAndWipeSeed(seed, { checks++ }) { response }
            }
            assertEquals(1, checks); assertArrayEquals(ByteArray(32), seed)
            assertTrue(response.all { it == 0.toByte() })
        }
    }
}
