package network.tos.security.pq

import android.util.Base64
import org.json.JSONObject
import java.security.MessageDigest

/** Created inside the native proof boundary, never from endpoint JSON.
 * Account binding does not by itself establish Quantum policy or signing authority.
 */
class QuantumVerifiedRead private constructor(json: ByteArray, request: ByteArray, anchor: ByteArray) {
    companion object {
        internal fun historical(anchor: ByteArray, request: ByteArray, priorState: ByteArray, localNow: Long,
                                kinds: IntArray, material: Array<ByteArray>): QuantumVerifiedRead {
            require(localNow > 0 && anchor.size in 1..1_048_576 && request.size in 1..1_048_576)
            val a = anchor.copyOf(); val r = request.copyOf()
            require(JSONObject(r.toString(Charsets.UTF_8)).getString("mode") == "historical")
            return QuantumVerifiedRead(QuantumProofNative.verify(a, r, priorState, localNow, kinds, material).verifiedJson, r, a)
        }
        internal fun live(directory: String, initialize: Boolean, anchor: ByteArray, request: ByteArray,
                          localNow: Long, transport: QuantumProofTransport): QuantumVerifiedRead {
            require(localNow > 0 && anchor.size in 1..1_048_576 && request.size in 1..1_048_576)
            val a = anchor.copyOf(); val r = request.copyOf()
            return QuantumVerifiedRead(QuantumProofNative.acquireLivePersisted(directory, initialize, a, r, localNow, transport), r, a)
        }
    }
    private val value = JSONObject(json.toString(Charsets.UTF_8))
    private val anchorDigest = digest(anchor)
    private val checkpoint = value.getJSONObject("target").let {
        listOf(it.getInt("workchain").toString(), it.getString("shard"), it.getLong("seqno").toString(),
               it.getString("root_hash"), it.getString("file_hash"), it.getLong("gen_utime").toString())
    }
    init {
        check(value.getString("status") == "verified" && value.getString("interface") == "tos-proof-verify/1" &&
              value.getString("request_sha256") == digest(request)) { "Proof request binding refused" }
    }
    /** Must be called again at the point of authorization, using the local clock/policy. */
    fun requireLive(localNow: Long, maximumAgeSeconds: Long) {
        require(localNow > 0 && maximumAgeSeconds in 1..604800)
        check(value.getString("mode") == "live") { "Historical proof cannot authorize a live operation" }
        val live = value.getJSONObject("live")
        val age = localNow - value.getJSONObject("target").getLong("gen_utime")
        check(age in -60..maximumAgeSeconds && maximumAgeSeconds <= live.getLong("max_age_seconds") &&
              localNow >= live.getLong("now")) { "Proof freshness refused" }
    }
    fun requireSameCheckpoint(other: QuantumVerifiedRead) {
        check(anchorDigest == other.anchorDigest && checkpoint == other.checkpoint) { "Proof checkpoint mismatch" }
    }
    /** Returns proven raw account state only after matching locally expected identity/code. */
    fun accountState(expectedAddress: String, expectedCodeHash: String): ByteArray {
        require(expectedAddress.matches(Regex("-?[0-9]+:[0-9a-f]{64}")) && expectedCodeHash.matches(Regex("[0-9a-f]{64}")))
        val account = value.getJSONObject("account")
        check(account.getBoolean("exists") && account.getBoolean("active") &&
              account.getString("address") == expectedAddress && account.getString("code_hash") == expectedCodeHash) {
            "Proof account binding refused"
        }
        return Base64.decode(account.getString("state_boc"), Base64.NO_WRAP).also { check(it.isNotEmpty()) }
    }
    /** Read another account/config at this authenticated point, retaining live rollback/freshness checks. */
    fun requestAtCheckpoint(account: String? = null, configIndices: IntArray = intArrayOf(), maximumAge: Long): ByteArray {
        require(maximumAge in 1..604800 && configIndices.size <= 64 && configIndices.all { it >= 0 } &&
                configIndices.toSet().size == configIndices.size && (account != null || configIndices.isNotEmpty()))
        require(account == null || account.matches(Regex("0:[0-9a-f]{64}")))
        val target = value.getJSONObject("target")
        val exact = JSONObject()
        for (field in listOf("workchain", "shard", "seqno", "root_hash", "file_hash")) exact.put(field, target.get(field))
        val request = JSONObject().put("mode", "live").put("target", exact).put("max_age_seconds", maximumAge)
        if (account != null) request.put("account", account)
        if (configIndices.isNotEmpty()) request.put("config_params", org.json.JSONArray(configIndices.toList()))
        return request.toString().toByteArray(Charsets.UTF_8)
    }
    val masterchainTime: Long get() = value.getJSONObject("target").getLong("gen_utime")
    fun accountTime(expectedAddress: String, expectedCodeHash: String): Long {
        accountState(expectedAddress, expectedCodeHash)
        return value.getJSONObject("account").getLong("gen_utime")
    }
    private fun configuration(index: Int): JSONObject {
        require(index >= 0)
        val params = value.getJSONArray("config_params")
        val matches = (0 until params.length()).map { params.getJSONObject(it) }.filter { it.getInt("index") == index }
        check(matches.size == 1) { "Proven configuration absent or ambiguous" }
        return matches.single()
    }
    /** Authenticated dynamic chain state, e.g. retirement policy. Schema/policy checks still apply. */
    fun provenConfigParam(index: Int): ByteArray =
        Base64.decode(configuration(index).getString("boc"), Base64.NO_WRAP).also { check(it.isNotEmpty()) }

    /** Additional local release freeze, e.g. a candidate gas configuration. */
    fun configParam(index: Int, expectedCellHash: String): ByteArray {
        require(expectedCellHash.matches(Regex("[0-9a-f]{64}")))
        val entry = configuration(index)
        check(entry.getString("cell_hash") == expectedCellHash) { "Proof configuration binding refused" }
        return Base64.decode(entry.getString("boc"), Base64.NO_WRAP).also { check(it.isNotEmpty()) }
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
