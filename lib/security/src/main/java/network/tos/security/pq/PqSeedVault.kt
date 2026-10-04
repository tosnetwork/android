package network.tos.security.pq

import android.content.SharedPreferences
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Dedicated authenticated PQ storage, accessed only with the unlocked wallet master key.
 * Caller owns password/PIN authentication. Records never share classical mnemonic IDs.
 * A 32-byte seed recreates the fixed algorithm; no expanded secret is persisted. */
class PqSeedVault(private val prefs: SharedPreferences) {
    private fun name(id: String, algorithm: PqAlgorithm): String {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        return "pq.v1.${algorithm.id}.$id"
    }
    @Synchronized fun create(id: String, algorithm: PqAlgorithm, master: SecretKey): ByteArray {
        val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return try { restore(id, algorithm, master, seed) } finally { seed.fill(0) }
    }
    @Synchronized fun restore(id: String, algorithm: PqAlgorithm, master: SecretKey, seed: ByteArray): ByteArray {
        val key = name(id, algorithm)
        require(seed.size == 32 && !prefs.contains(key)) { "Invalid seed or existing PQ key" }
        val publicKey = requireNotNull(PqNative.publicKey(algorithm.id, seed)) { "PQ key generation failed" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, master)
        cipher.updateAAD(key.toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(seed)
        val record = cipher.iv + ciphertext
        try {
            check(prefs.edit().putString(key, Base64.encodeToString(record, Base64.NO_WRAP)).commit()) { "PQ key persistence failed" }
        } finally { ciphertext.fill(0); record.fill(0) }
        return publicKey
    }
    private inline fun <T> withSeed(id: String, algorithm: PqAlgorithm, master: SecretKey, block: (ByteArray) -> T): T {
        val key = name(id, algorithm)
        val record = Base64.decode(requireNotNull(prefs.getString(key, null)) { "Missing PQ key" }, Base64.NO_WRAP)
        require(record.size == 60) { "Malformed PQ key record" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, master, GCMParameterSpec(128, record.copyOfRange(0, 12)))
        cipher.updateAAD(key.toByteArray(Charsets.UTF_8))
        val seed = try { cipher.doFinal(record, 12, record.size - 12) } finally { record.fill(0) }
        return try { require(seed.size == 32); block(seed) } finally { seed.fill(0) }
    }
    @Synchronized fun publicKey(id: String, algorithm: PqAlgorithm, master: SecretKey): ByteArray =
        withSeed(id, algorithm, master) { requireNotNull(PqNative.publicKey(algorithm.id, it)) }
    @Synchronized fun sign(id: String, algorithm: PqAlgorithm, master: SecretKey, expectedPublicKey: ByteArray, message: ByteArray): ByteArray =
        withSeed(id, algorithm, master) { seed ->
            require(requireNotNull(PqNative.publicKey(algorithm.id, seed)).contentEquals(expectedPublicKey)) { "PQ key binding mismatch" }
            val entropy = ByteArray(48).also { SecureRandom().nextBytes(it) }
            try { requireNotNull(PqNative.sign(algorithm.id, seed, entropy, message)) { "PQ signing failed" } }
            finally { entropy.fill(0) }
        }
    @Synchronized fun backup(id: String, algorithm: PqAlgorithm, network: Int, master: SecretKey,
        password: CharArray): ByteArray = withSeed(id, algorithm, master) { seed ->
            PqBackup.encrypt(algorithm, network, requireNotNull(PqNative.publicKey(algorithm.id, seed)), seed, password)
        }
    @Synchronized fun restoreBackup(id: String, algorithm: PqAlgorithm, network: Int, master: SecretKey,
        record: ByteArray, password: CharArray): ByteArray {
        val seed = PqBackup.decrypt(record, algorithm, network, password)
        return try { restore(id, algorithm, master, seed) } finally { seed.fill(0) }
    }
    @Synchronized fun delete(id: String, algorithm: PqAlgorithm) {
        check(prefs.edit().remove(name(id, algorithm)).commit()) { "PQ key deletion failed" }
    }
}
