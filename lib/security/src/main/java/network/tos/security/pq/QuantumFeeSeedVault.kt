package network.tos.security.pq

import android.content.SharedPreferences
import android.util.Base64
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal object FeeSeedNative {
    init { System.loadLibrary("tos_mobile_pq") }
    external fun bind(seed: ByteArray, leaf: Long, path: ByteArray, key: ByteArray): Boolean
}

/** Encrypted fee seed only. Restore never reconstructs leaf state; the journal's restart barrier still applies.
 * Authentication and chain proof/expiry/broadcast checks remain wallet service requirements. */
class QuantumFeeSeedVault(private val prefs: SharedPreferences) {
    class Enrollment(val globalId: Int, network: ByteArray, vault: ByteArray, tree: ByteArray,
                     val epoch: Long, publicKey: ByteArray, account: Long, generation: Long) {
        internal val network = network.copyOf()
        internal val vault = vault.copyOf()
        internal val tree = tree.copyOf()
        internal val key = publicKey.copyOf()
        private val account = account
        private val generation = generation
        init {
            require(this.network.size == 32 && this.vault.size == 32 && this.tree.size == 32 && key.size == 60)
            require(epoch in 0..0xffffffffL && account in 0..0xffffffffL && generation in 0..0xffffffffL)
        }
        internal fun encoding(): ByteArray = ByteBuffer.allocate(172).putInt(globalId).put(network)
            .put(vault).put(tree).putInt(epoch.toInt()).put(key).putInt(account.toInt()).putInt(generation.toInt()).array()
    }
    private fun name(id: String): String {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        return "quantum.fee.seed.v1.$id"
    }
    private fun aad(name: String, enrollment: Enrollment): ByteArray =
        "TOS-V5R2-FEE-SEED-v1".toByteArray(Charsets.US_ASCII) + byteArrayOf(name.length.toByte()) +
            name.toByteArray(Charsets.US_ASCII) + enrollment.encoding()
    fun importAndWipe(id: String, enrollment: Enrollment, wrappingKey: SecretKey, seed: ByteArray,
                      leaf: Long, path: ByteArray): Unit = synchronized(writerLock) {
        try {
            val name = name(id)
            require(seed.size == 48 && path.size == 640 && leaf in 0 until (1L shl 20) && !prefs.contains(name))
            require(FeeSeedNative.bind(seed, leaf, path, enrollment.key)) { "Fee seed does not bind enrollment" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey); cipher.updateAAD(aad(name, enrollment))
            val ciphertext = cipher.doFinal(seed)
            val record = byteArrayOf(1) + cipher.iv + ciphertext
            try { check(prefs.edit().putString(name, Base64.encodeToString(record, Base64.NO_WRAP)).commit()) }
            finally { ciphertext.fill(0); record.fill(0) }
        } finally { seed.fill(0) }
    }
    fun sign(id: String, enrollment: Enrollment, wrappingKey: SecretKey, session: QuantumFeeState,
             time: Long, chainNext: Long, leaf: Long, digest: ByteArray, path: ByteArray): ByteArray = synchronized(writerLock) {
        require(session.matchesRoute(enrollment.globalId, enrollment.network, enrollment.vault, enrollment.tree, enrollment.epoch)) {
            "Fee custody route does not match signing session"
        }
        val name = name(id)
        val record = Base64.decode(checkNotNull(prefs.getString(name, null)), Base64.NO_WRAP)
        try {
            require(record.size == 77 && record[0] == 1.toByte())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(128, record.copyOfRange(1, 13)))
            cipher.updateAAD(aad(name, enrollment))
            val seed = cipher.doFinal(record, 13, record.size - 13)
            try { session.signOnceAndWipe(time, chainNext, leaf, digest, enrollment.key, seed, path) }
            finally { seed.fill(0) }
        } finally { record.fill(0) }
    }
    companion object { private val writerLock = Any() }
}
