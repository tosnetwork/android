package network.tos.wallet.api.tos

/**
 * Cross-endpoint corroborating `.tos` resolver (W1 mitigation).
 *
 * The bare [TosDnsResolver] reads the real on-chain Domain contracts, but through a single
 * JSON-RPC node whose answers it cannot cryptographically verify (the node surface does not yet
 * expose Merkle proofs). A malicious, compromised, or MITM'd node can therefore forge a
 * `.tos -> address` mapping and misdirect a payment.
 *
 * This resolver runs the SAME resolution through two or more INDEPENDENT nodes and returns the
 * evidence only if every node agrees on the resolved wallet address and resolver path (see
 * [TosDnsResolver.corroborate]). It raises the bar from "one lying endpoint is enough" to
 * "every configured endpoint must collude (or all be MITM'd) and the resolver paths must match."
 * It is defense-in-depth, not a replacement for proof verification (Tier 2), which remains blocked
 * on the node exposing account-state proofs.
 *
 * All sources MUST point at independent operators for the corroboration to mean anything; passing
 * the same endpoint twice provides no protection.
 */
class CorroboratingTosDns(private val sources: List<TosSource>) {

    init {
        require(sources.size >= 2) {
            "cross-endpoint DNS corroboration requires >= 2 independent sources"
        }
    }

    /**
     * Resolve [input] to its wallet address through every source and return the agreed evidence,
     * or throw if any endpoint disagrees or fails. The same [now] is applied to every source so the
     * lease/auction gate is evaluated identically across nodes.
     */
    fun resolveWallet(
        input: String,
        testnet: Boolean = false,
        now: Long = System.currentTimeMillis() / 1000,
    ): TosDnsEvidence =
        TosDnsResolver.corroborate(
            sources.map { TosDnsResolver(it).resolveWallet(input, testnet, now) },
        )
}
