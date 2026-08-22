package network.tos.wallet.api.tos

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TosDnsResolverTest {
    @Test
    fun `canonical TIP-1 corpus is consumed without semantic copies`() {
        val raw = checkNotNull(javaClass.classLoader?.getResource("tip-1-dns-v1.json")).readText()
        val corpus = JSONObject(raw)
        assertEquals("tos.tip-1.dns-v1.v1", corpus.getString("schema"))
        assertEquals(
            TosDnsResolver.MAX_RESOLVER_CONTACTS,
            corpus.getJSONObject("resolver_policy").getInt("maximum_contacts"),
        )
        assertEquals(31_622_400L, corpus.getJSONObject("lifecycle").getLong("renewal_interval_seconds"))
        assertEquals(
            TosDnsResolver.WALLET_CATEGORY.toString(16),
            corpus.getJSONObject("categories").getJSONObject("wallet").getString("sha256"),
        )
        val vectors = corpus.getJSONArray("name_encoding")
        for (index in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(index)
            if (vector.getString("result") == "accept") {
                assertArrayEquals(hex(vector.getString("encoded_hex")), TosDnsResolver.encodeName(vector.getString("input")))
            }
        }
    }

    @Test
    fun `TIP-1 name vectors are encoded byte for byte`() {
        assertEquals("alice.tos", TosDnsResolver.canonicalName("Alice.tos"))
        assertArrayEquals(
            hex("746f7300616c69636500"),
            TosDnsResolver.encodeName("alice.tos"),
        )
        assertArrayEquals(
            hex("746f7300616c696365007472616e736c61746500"),
            TosDnsResolver.encodeName("translate.alice.tos"),
        )
    }

    @Test
    fun `invalid and non TOS names fail before network access`() {
        listOf("alice.tos.", "alice..tos", "älice.tos", "alice.ton", " alice.tos").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { TosDnsResolver.canonicalName(name) }
        }
    }

    @Test
    fun `TON renewal boundary is inclusive and overflow fails closed`() {
        assertTrue(TosDnsResolver.isLeaseUsable(1_000, 31_623_400))
        assertFalse(TosDnsResolver.isLeaseUsable(1_000, 31_623_401))
        assertFalse(TosDnsResolver.isLeaseUsable(0, 0))
        assertFalse(TosDnsResolver.isLeaseUsable(Long.MAX_VALUE, Long.MAX_VALUE))
    }

    @Test
    fun `resolver contact budget stays at TIP-1 value`() {
        assertEquals(8, TosDnsResolver.MAX_RESOLVER_CONTACTS)
    }

    @Test
    fun `root and collection may stop immediately before separator`() {
        val query = TosDnsResolver.encodeName("alice.tos")
        assertTrue(TosDnsResolver.isComponentBoundary(3, query))
        assertTrue(TosDnsResolver.isComponentBoundary(4, query))
        assertFalse(TosDnsResolver.isComponentBoundary(2, query))
    }

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
