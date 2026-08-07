package network.tos.wallet.data.rn.data

import network.tos.security.CryptoBox
import network.tos.security.Security
import network.tos.security.hex
import org.json.JSONArray
import org.json.JSONObject

data class RNTCConnection(
    val type: String,
    val sessionKeyPair: RNTCKeyPair?,
    val clientSessionId: String?,
) {

    companion object {

        fun toJSONArray(connections: List<RNTCConnection>): JSONArray {
            val array = JSONArray()
            connections.forEach { array.put(it.toJSON()) }
            return array
        }
    }

    val keyPair: CryptoBox.KeyPair by lazy {
        sessionKeyPair?.let {
            CryptoBox.KeyPair(it.publicKey.hex(), it.secretKey.hex())
        } ?: CryptoBox.keyPair()
    }

    val clientId: String by lazy {
        clientSessionId ?: hex(Security.randomBytes(16))
    }

    constructor(json: JSONObject) : this(
        type = json.getString("type"),
        sessionKeyPair = json.optJSONObject("sessionKeyPair")?.let { RNTCKeyPair(it) },
        clientSessionId = json.optString("clientSessionId").ifBlank { null }
    )

    fun toJSON(): JSONObject {
        val json = JSONObject()
        json.put("type", type)
        json.put("replyItems", JSONArray())
        sessionKeyPair?.let { json.put("sessionKeyPair", it.toJSON()) }
        clientSessionId?.let { json.put("clientSessionId", it) }
        return json
    }
}