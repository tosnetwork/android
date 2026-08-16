package network.tos.agentcommerce

import org.junit.Assert.assertEquals
import org.junit.Test

class FinalityQuorumTest {
    private val network = NetworkTuple("local", "root", "file")
    private val endpoints = listOf("http://node1", "http://node2", "http://node3")

    @Test
    fun `requires two distinct matching finalized nodes`() {
        val observations = listOf(
            FinalizedObservation(endpoints[0], network, "b1", "s1", true),
            FinalizedObservation(endpoints[1], network, "b1", "s1", true),
            FinalizedObservation(endpoints[2], network, "b2", "s2", true),
        )
        assertEquals(
            FinalityDecision.Finalized("b1", "s1", 2),
            FinalityQuorum.decide(endpoints, network, observations),
        )
    }

    @Test
    fun `duplicate wrong-network and unfinalized replies do not count`() {
        val other = NetworkTuple("other", "root", "file")
        val observations = listOf(
            FinalizedObservation(endpoints[0], network, "b1", "s1", true),
            FinalizedObservation(endpoints[0], network, "b1", "s1", true),
            FinalizedObservation(endpoints[1], other, "b1", "s1", true),
            FinalizedObservation(endpoints[2], network, "b1", "s1", false),
        )
        assertEquals(FinalityDecision.Pending, FinalityQuorum.decide(endpoints, network, observations))
        assertEquals(
            FinalityDecision.InvalidConfiguration,
            FinalityQuorum.decide(listOf(endpoints[0], endpoints[0], endpoints[2]), network, emptyList()),
        )
    }
}
