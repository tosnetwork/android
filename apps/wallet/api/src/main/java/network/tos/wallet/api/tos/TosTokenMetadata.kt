package network.tos.wallet.api.tos

import org.json.JSONObject

/** Parses the node contract independently of local asset class names. */
internal class TosTokenMetadata(private val data: JSONObject) {
    init {
        require(data.optString("@type") == "ext.tokens.tokenMasterData") { "Invalid token master response" }
    }

    val uri: String get() = data.optString("token_metadata_uri")

    private fun value(nodeKey: String, jsonKey: String, offchain: JSONObject?): String? =
        data.optString(nodeKey).takeIf { it.isNotBlank() }
            ?: offchain?.optString(jsonKey)?.takeIf { it.isNotBlank() }

    fun symbol(offchain: JSONObject?): String = value("token_symbol", "symbol", offchain) ?: "JETTON"
    fun name(master: String, offchain: JSONObject?): String =
        value("token_name", "name", offchain) ?: "Jetton ${master.takeLast(6)}"
    fun decimals(offchain: JSONObject?): Int = value("token_decimals", "decimals", offchain)?.toIntOrNull() ?: 9
    fun image(offchain: JSONObject?): String? = value("token_image", "image", offchain)
}
