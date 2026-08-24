package network.tos.wallet.api.tos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CorroboratingTosDnsTest {

    private fun blockId(seqno: Int, root: String) = TosBlockId(
        type = "tos.blockIdExt",
        workchain = -1,
        shard = "8000000000000000",
        seqno = seqno,
        rootHash = root,
        fileHash = "ff",
    )

    private fun evidence(
        address: String,
        path: List<String> = listOf("-1:root", "0:collection", "0:item"),
        name: String = "alice.tos",
        checkpoint: TosBlockId = blockId(100, "aa"),
        renewalDeadline: Long = 1_800_000_000L,
    ) = TosDnsEvidence(
        canonicalName = name,
        address = address,
        checkpoint = checkpoint,
        resolverPath = path,
        renewalDeadline = renewalDeadline,
    )

    @Test
    fun `agreeing endpoints return the shared evidence even with different checkpoints`() {
        // Two honest nodes at different finalized blocks agree on the resolved address & path.
        val a = evidence(address = "0:abc", checkpoint = blockId(100, "aa"), renewalDeadline = 1_800_000_000L)
        val b = evidence(address = "0:abc", checkpoint = blockId(103, "bb"), renewalDeadline = 1_800_000_050L)
        val result = TosDnsResolver.corroborate(listOf(a, b))
        assertEquals("0:abc", result.address)
        assertEquals(a, result) // primary (first) is returned
    }

    @Test
    fun `a single endpoint forging a different address is rejected`() {
        val honest = evidence(address = "0:abc")
        val forged = evidence(address = "0:deadbeef") // a lying / MITM'd node
        val ex = assertThrows(IllegalArgumentException::class.java) {
            TosDnsResolver.corroborate(listOf(honest, forged))
        }
        assertEquals(true, ex.message?.contains("disagreement"))
    }

    @Test
    fun `a divergent resolver path is rejected even when the final address matches`() {
        val honest = evidence(address = "0:abc", path = listOf("-1:root", "0:collection", "0:item"))
        val rerouted = evidence(address = "0:abc", path = listOf("-1:root", "0:evil-collection", "0:item"))
        assertThrows(IllegalArgumentException::class.java) {
            TosDnsResolver.corroborate(listOf(honest, rerouted))
        }
    }

    @Test
    fun `a divergent canonical name is rejected`() {
        val a = evidence(address = "0:abc", name = "alice.tos")
        val b = evidence(address = "0:abc", name = "bob.tos")
        assertThrows(IllegalArgumentException::class.java) {
            TosDnsResolver.corroborate(listOf(a, b))
        }
    }

    @Test
    fun `fewer than two results fails closed`() {
        assertThrows(IllegalArgumentException::class.java) {
            TosDnsResolver.corroborate(listOf(evidence(address = "0:abc")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TosDnsResolver.corroborate(emptyList())
        }
    }

    @Test
    fun `three-way agreement passes and a single dissenter fails`() {
        val a = evidence(address = "0:abc", checkpoint = blockId(100, "aa"))
        val b = evidence(address = "0:abc", checkpoint = blockId(101, "bb"))
        val c = evidence(address = "0:abc", checkpoint = blockId(102, "cc"))
        assertEquals("0:abc", TosDnsResolver.corroborate(listOf(a, b, c)).address)

        val dissenter = evidence(address = "0:evil", checkpoint = blockId(102, "cc"))
        assertThrows(IllegalArgumentException::class.java) {
            TosDnsResolver.corroborate(listOf(a, b, dissenter))
        }
    }
}
