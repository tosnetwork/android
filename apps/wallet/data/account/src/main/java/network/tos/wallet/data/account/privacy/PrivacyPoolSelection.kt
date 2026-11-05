package network.tos.wallet.data.account.privacy

import android.content.Context
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import network.tos.wallet.data.account.pq.QuantumNetworkProfile
import org.json.JSONObject
import org.ton.block.AddrStd
import org.ton.boc.BagOfCells
import org.ton.cell.CellType

/** Explicit trust input, never discovered from an RPC reply. Structural parsing does not authenticate its source. */
@OptIn(ExperimentalEncodingApi::class)
class PrivacyPoolSelection(val profile: QuantumNetworkProfile, pool: String, codeBOC: ByteArray,
    profileHash: String, manifestHash: String, verifyingKeyHash: String,
) {
    val pool: String = pool.also { require(it.matches(Regex("0:[0-9a-f]{64}"))) }
    private val code = codeBOC.also { require(it.size in 1..131072) }.copyOf()
    val profileHash = hash(profileHash)
    val manifestHash = hash(manifestHash)
    val verifyingKeyHash = hash(verifyingKeyHash)
    init { expectedCode() }
    internal fun address() = AddrStd.parse(pool)
    internal fun expectedCode() = BagOfCells(code.copyOf()).roots.single().also {
        require(it.type == CellType.ORDINARY && it.levelMask.level == 0)
    }
    internal fun requireProfile(selected: QuantumNetworkProfile) {
        require(profile.globalId == selected.globalId && profile.rootHash == selected.rootHash && profile.fileHash == selected.fileHash) {
            "Privacy pool belongs to another trusted network"
        }
    }
    internal fun json(): JSONObject = JSONObject().put("version", 1).put("profile", profile.json())
        .put("pool", pool).put("code_boc", Base64.Default.encode(code))
        .put("profile_hash", profileHash).put("manifest_hash", manifestHash).put("verifying_key_hash", verifyingKeyHash)
    internal fun profileBytes() = decodeHash(profileHash)
    internal fun manifestBytes() = decodeHash(manifestHash)
    internal fun verifyingKeyBytes() = decodeHash(verifyingKeyHash)
    companion object {
        private fun hash(value: String) = value.also { require(it.matches(Regex("[0-9a-f]{64}")) && it.any { c -> c != '0' }) }
        private fun decodeHash(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        internal fun parse(value: JSONObject): PrivacyPoolSelection {
            require(value.keys().asSequence().toSet() == setOf("version", "profile", "pool", "code_boc", "profile_hash", "manifest_hash", "verifying_key_hash"))
            require(value.get("version") == 1)
            val encoded = value.getString("code_boc"); require(encoded.length <= 174764)
            val code = Base64.Default.decode(encoded); require(Base64.Default.encode(code) == encoded)
            return PrivacyPoolSelection(QuantumNetworkProfile.parse(value.getJSONObject("profile")), value.getString("pool"), code,
                value.getString("profile_hash"), value.getString("manifest_hash"), value.getString("verifying_key_hash"))
        }
    }
}

/** Public local trust configuration only. Notes, master material and reservation state never enter preferences. */
internal class PrivacyPoolRegistry(context: Context) {
    private val prefs = context.getSharedPreferences("tos_privacy_explicit_pool_v1", Context.MODE_PRIVATE)
    private fun key(p: QuantumNetworkProfile) = "${p.globalId}.${p.rootHash}.${p.fileHash}"
    fun selected(profile: QuantumNetworkProfile): PrivacyPoolSelection? {
        val raw = prefs.getString(key(profile), null) ?: return null
        require(raw.length <= 196608) { "Privacy configuration exceeds budget" }
        return PrivacyPoolSelection.parse(JSONObject(raw)).also { it.requireProfile(profile) }
    }
    /** Only the explicit configuration API calls this, after validating those same caller-supplied pins on-chain. */
    fun saveExplicit(selection: PrivacyPoolSelection) {
        val name = key(selection.profile)
        val raw = selection.json().toString()
        require(raw.length <= 196608)
        synchronized(writeLock) {
            check(prefs.contains(name) || prefs.all.size < 16) { "Privacy network configuration capacity exceeded" }
            check(prefs.edit().putString(name, raw).commit()) { "Privacy trust configuration persistence uncertain" }
        }
    }

    companion object {
        private val writeLock = Any()
    }
}
