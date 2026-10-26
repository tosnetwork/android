package network.tos.wallet.data.account.pq

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/** Explicit local trust configuration. RPC responses cannot select or replace this anchor. */
class QuantumNetworkProfile(val globalId: Int, rootHash: String, fileHash: String) {
    val rootHash = hash(rootHash)
    val fileHash = hash(fileHash)

    fun anchor(): ByteArray = JSONObject().put("kind", "zerostate").put("workchain", -1)
        .put("shard", "8000000000000000").put("seqno", 0)
        .put("root_hash", rootHash).put("file_hash", fileHash).toString().toByteArray(Charsets.UTF_8)

    internal fun json(): JSONObject = JSONObject().put("global_id", globalId)
        .put("root_hash", rootHash).put("file_hash", fileHash)

    fun save(context: Context) {
        check(preferences(context).edit().putString(KEY, json().toString()).commit()) { "Network configuration could not be saved" }
    }

    companion object {
        private const val KEY = "trusted_network_v1"
        private fun preferences(context: Context) = context.getSharedPreferences("tos_quantum_network", Context.MODE_PRIVATE)
        private fun hash(value: String): String {
            val result = value.trim().lowercase(Locale.ROOT)
            require(result.matches(Regex("[0-9a-f]{64}")) && result.any { it != '0' }) { "Enter a nonzero 64-digit network hash" }
            return result
        }
        internal fun parse(value: JSONObject): QuantumNetworkProfile {
            require(value.keys().asSequence().toSet() == setOf("global_id", "root_hash", "file_hash"))
            val id = value.get("global_id")
            require(id is Int || id is Long)
            val number = (id as Number).toLong()
            require(number in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
            return QuantumNetworkProfile(number.toInt(), value.getString("root_hash"), value.getString("file_hash"))
        }
        fun selected(context: Context): QuantumNetworkProfile? {
            val value = preferences(context).getString(KEY, null) ?: return null
            require(value.length <= 1024) { "Invalid network configuration" }
            return parse(JSONObject(value))
        }
    }
}
