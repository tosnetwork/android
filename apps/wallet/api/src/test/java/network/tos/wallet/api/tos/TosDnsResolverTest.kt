package network.tos.wallet.api.tos

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TosDnsResolverTest {
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
