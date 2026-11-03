package network.tos.wallet.data.account.privacy

import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class PrivacyDescriptorTest {
    private fun fixture() = ByteArray(2573).apply {
        byteArrayOf(0x53, 0x48, 0x44, 0x31, 1).copyInto(this)
        for (i in 37 until 45) this[i] = 0xff.toByte()
    }

    @Test fun unsignedIndexAndImmutableReviewSnapshot() {
        val bytes = fixture()
        val parsed = PrivacyDescriptor.parse(bytes, ByteArray(32))
        assertEquals(BigInteger("18446744073709551615"), parsed.noteKeyIndex)
        bytes[0] = 0
        parsed.bytes[0] = 0
        assertEquals(0x53.toByte(), parsed.bytes[0])
        assertArrayEquals(ByteArray(32), parsed.executionDomain)
        assertArrayEquals(parsed.bytes, PrivacyDescriptor.parse(parsed.base64, ByteArray(32)).bytes)
    }

    @Test fun descriptorsIssuedByPinnedRustWallet() {
        val text = requireNotNull(javaClass.getResourceAsStream("/privacy-descriptors.json")).bufferedReader().use { it.readText() }
        val vectors = JSONObject(text).getJSONArray("vectors")
        assertEquals(2, vectors.length())
        val domain = ByteArray(32).apply { this[31] = 7 }
        for (i in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(i)
            val descriptor = PrivacyDescriptor.parse(vector.getString("descriptor"), domain)
            assertEquals(vector.getString("index"), descriptor.noteKeyIndex.toString())
            assertEquals(vector.getString("descriptor"), descriptor.base64)
        }
    }

    @Test fun wrongPoolMalformedFieldsAndLengthsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            PrivacyDescriptor.parse(fixture(), ByteArray(32) { 1 })
        }
        for (offset in listOf(0, 4, 5, 45)) {
            val bytes = fixture().apply { this[offset] = 0xff.toByte() }
            assertThrows(IllegalArgumentException::class.java) { PrivacyDescriptor.parse(bytes, ByteArray(32)) }
        }
        assertThrows(IllegalArgumentException::class.java) { PrivacyDescriptor.parse("invalid", ByteArray(32)) }
        for (size in listOf(0, 2572, 2574)) {
            assertThrows(IllegalArgumentException::class.java) { PrivacyDescriptor.parse(fixture().copyOf(size), ByteArray(32)) }
        }
    }
}
