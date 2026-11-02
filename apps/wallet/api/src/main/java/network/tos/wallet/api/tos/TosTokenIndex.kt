package network.tos.wallet.api.tos

import org.json.JSONObject
import java.util.Locale

/** Untrusted discovery hints; no entry or completeness flag proves a balance. */
class TosTokenIndex private constructor(
    val positions: List<Position>, val nodeReportsComplete: Boolean, val reachedRowLimit: Boolean,
) {
    data class Position(val wallet: String, val master: String)
    companion object {
        const val MAXIMUM_ROWS = 10_000
        fun decode(value: JSONObject): TosTokenIndex {
            require(value.getString("@type") == "wallet.accountTokens")
            val rows = value.getJSONArray("tokens")
            require(rows.length() <= MAXIMUM_ROWS)
            val wallets = HashSet<String>(); val masters = HashSet<String>()
            fun address(value: String): String {
                require(value.matches(Regex("0:[0-9a-fA-F]{64}")))
                return value.lowercase(Locale.ROOT)
            }
            val positions = (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                val wallet = address(row.getString("token_wallet"))
                val master = address(row.getString("token_master"))
                require(wallets.add(wallet) && masters.add(master)) { "Duplicate Token index position" }
                Position(wallet, master)
            }
            val state = value.optJSONObject("index_state")
            val reported = state?.opt("complete") == true && state.opt("ownership_complete") != false
            return TosTokenIndex(positions, reported, rows.length() == MAXIMUM_ROWS)
        }
    }
}
