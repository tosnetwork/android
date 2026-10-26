package network.tos.wallet.app.ui.screen.init

import network.tos.blockchain.ton.contract.WalletVersion

/** Restore every discovered asset-bearing account without exposing version choices.
 * Incomplete/unavailable asset discovery retains the account rather than hiding it. */
internal object LegacyRecoverySelection {
    data class Candidate(val address: String, val version: WalletVersion, val hasAssets: Boolean,
                         val assetsKnown: Boolean = true)

    fun select(candidates: List<Candidate>, fallback: WalletVersion, recoveryAddress: String? = null): List<Int> {
        require(candidates.isNotEmpty() && candidates.size <= 64)
        require(candidates.map { it.address }.distinct().size == candidates.size)
        if (recoveryAddress != null) {
            return listOf(candidates.indexOfFirst { it.address == recoveryAddress }.also { require(it >= 0) })
        }
        val retained = candidates.indices.filter { candidates[it].hasAssets || !candidates[it].assetsKnown }
        return retained.ifEmpty {
            listOf(candidates.indexOfFirst { it.version == fallback }.also { require(it >= 0) })
        }
    }

    fun names(base: String, count: Int, existing: Int, defaultName: Boolean): List<String> {
        require(count > 0 && existing >= 0)
        return List(count) { index ->
            if (count > 1 || (defaultName && existing > 0)) "$base ${existing + index + 1}" else base
        }
    }
}
