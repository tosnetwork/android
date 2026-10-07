package network.tos.security.pq

import android.content.SharedPreferences
import android.util.Base64
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Role-specific encrypted seed custody. Authentication and chain authorization belong to the wallet service.
 * Separate rescue custody requires storing its record on the independent device. No fee state is stored here. */
class V5R2SeedVault(private val prefs: SharedPreferences) {
    enum class MasterProfile { NATIVE_MNEMONIC, RAW_MASTER_32 }
    class Context(network: ByteArray, val globalId: Int, val account: Long, val generation: Long) {
        private val tag = network.copyOf()
        init { require(tag.size == 32 && account in 0..0xffffffffL && generation in 0..0xffffffffL) }
        internal fun encoding(): ByteArray = ByteBuffer.allocate(44).put(tag).putInt(globalId)
            .putInt(account.toInt()).putInt(generation.toInt()).array()
    }
    private fun name(id: String, role: V5R2Role): String {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        return "v5r2.seed.v1.${role.id}.$id"
    }
    private fun aad(name: String, context: Context): ByteArray =
        "TOS-V5R2-SEED-v1".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(name.length.toByte()) + name.toByteArray(Charsets.US_ASCII) + context.encoding()

    /** Initial key binding only. No signing handle or current-authority approval is returned.
     * Native mnemonic validation must precede this call for that input profile. */
    fun restoreDerivedAndWipe(id: String, role: V5R2Role, context: Context, wrappingKey: SecretKey,
                              master: ByteArray, inputProfile: MasterProfile, declaredProfile: MasterProfile,
                              expectedPublicKey: ByteArray): ByteArray {
        var seed = ByteArray(0)
        try {
            require(inputProfile == declaredProfile && expectedPublicKey.size == role.publicKeySize)
            val encoded = context.encoding()
            seed = V5R2Kdf.deriveAndWipe(if (role == V5R2Role.PRIMARY) V5R2Kdf.Material.PRIMARY else V5R2Kdf.Material.RESCUE,
                master, encoded.copyOfRange(0, 32), context.globalId, context.account, context.generation)
            require(V5R2Crypto.publicKey(role, seed).contentEquals(expectedPublicKey)) { "Recovered key differs from enrollment" }
            return importAndWipe(id, role, context, wrappingKey, seed)
        } finally { master.fill(0); seed.fill(0) }
    }

    /** Takes ownership of the caller's seed and clears it even if validation or persistence fails. */
    fun importAndWipe(id: String, role: V5R2Role, context: Context, wrappingKey: SecretKey,
                      seed: ByteArray): ByteArray = synchronized(writerLock) {
        try {
            val name = name(id, role)
            require(seed.size == role.seedSize && !prefs.contains(name))
            val publicKey = V5R2Crypto.publicKey(role, seed)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey)
            cipher.updateAAD(aad(name, context))
            val ciphertext = cipher.doFinal(seed)
            val record = byteArrayOf(1) + cipher.iv + ciphertext
            try {
                check(prefs.edit().putString(name, Base64.encodeToString(record, Base64.NO_WRAP)).commit())
            } finally { ciphertext.fill(0); record.fill(0) }
            publicKey
        } finally { seed.fill(0) }
    }
    private fun <T> withSeed(id: String, role: V5R2Role, context: Context, wrappingKey: SecretKey,
                             body: (ByteArray) -> T): T = synchronized(writerLock) {
        val name = name(id, role)
        val record = Base64.decode(checkNotNull(prefs.getString(name, null)), Base64.NO_WRAP)
        try {
            require(record.size == 29 + role.seedSize && record[0] == 1.toByte())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(128, record.copyOfRange(1, 13)))
            cipher.updateAAD(aad(name, context))
            val seed = cipher.doFinal(record, 13, record.size - 13)
            try { require(seed.size == role.seedSize); body(seed) } finally { seed.fill(0) }
        } finally { record.fill(0) }
    }
    fun publicKey(id: String, role: V5R2Role, context: Context, wrappingKey: SecretKey): ByteArray =
        withSeed(id, role, context, wrappingKey) { V5R2Crypto.publicKey(role, it) }
    fun sign(id: String, role: V5R2Role, context: Context, wrappingKey: SecretKey,
             expectedPublicKey: ByteArray, purpose: V5R2Purpose, digest: ByteArray): ByteArray =
        withSeed(id, role, context, wrappingKey) {
            V5R2Crypto.sign(role, purpose, it, expectedPublicKey, digest)
        }
    companion object { private val writerLock = Any() }
}
