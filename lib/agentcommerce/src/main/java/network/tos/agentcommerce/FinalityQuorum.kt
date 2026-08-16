package network.tos.agentcommerce

data class FinalizedObservation(
    val endpoint: String,
    val network: NetworkTuple,
    val blockRoot: String,
    val stateDigest: String,
    val finalized: Boolean,
)

sealed interface FinalityDecision {
    data object InvalidConfiguration : FinalityDecision
    data object Pending : FinalityDecision
    data class Finalized(val blockRoot: String, val stateDigest: String, val votes: Int) : FinalityDecision
}

/**
 * Accepts state only after distinct configured nodes attest the exact same
 * finalized block and state on the expected network. Duplicate/conflicting
 * replies from one endpoint cannot manufacture quorum.
 */
object FinalityQuorum {
    fun decide(
        configuredEndpoints: List<String>,
        expectedNetwork: NetworkTuple,
        observations: List<FinalizedObservation>,
        quorum: Int = 2,
    ): FinalityDecision {
        if (configuredEndpoints.size != 3 || configuredEndpoints.toSet().size != 3 ||
            configuredEndpoints.any { it.isEmpty() || it.trim() != it } ||
            quorum !in 2..configuredEndpoints.size
        ) {
            return FinalityDecision.InvalidConfiguration
        }
        val configured = configuredEndpoints.toSet()
        val votes = mutableMapOf<Pair<String, String>, Int>()
        observations.filter { it.endpoint in configured }.groupBy { it.endpoint }.values
            .filter { it.size == 1 }
            .map { it.single() }
            .filter {
                it.finalized && it.network == expectedNetwork &&
                    it.blockRoot.isNotEmpty() && it.stateDigest.isNotEmpty()
            }
            .forEach { value ->
                val key = value.blockRoot to value.stateDigest
                votes[key] = (votes[key] ?: 0) + 1
            }
        val winner = votes.entries.firstOrNull { it.value >= quorum } ?: return FinalityDecision.Pending
        return FinalityDecision.Finalized(winner.key.first, winner.key.second, winner.value)
    }
}
