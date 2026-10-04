package network.tos.security.pq

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import org.bouncycastle.crypto.PBEParametersGenerator
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.KeyParameter
import javax.crypto.spec.SecretKeySpec

/** Portable authenticated v1 seed backup. Fixed PBKDF2-SHA256/600000 + AES256-GCM.
 * Header binds algorithm, chain and SHA256(public key). Never accept a classical seed backup. */
object PqBackup {
    private val magic = "TOSPQB01".toByteArray(Charsets.US_ASCII)
    private fun key(password: CharArray, salt: ByteArray): ByteArray {
        require(password.size >= 12) { "Backup password must contain at least 12 characters" }
        // Use the lightweight SHA256 implementation, including on API 24/25 where
        // the platform PBKDF2WithHmacSHA256 provider is unavailable.
        val encoded = PBEParametersGenerator.PKCS5PasswordToUTF8Bytes(password)
        return try {
            val generator = PKCS5S2ParametersGenerator(SHA256Digest())
            generator.init(encoded, salt, 600000)
            (generator.generateDerivedParameters(256) as KeyParameter).key
        } finally { encoded.fill(0) }
    }
    fun encrypt(algorithm: PqAlgorithm, network: Int, publicKey: ByteArray, seed: ByteArray, password: CharArray): ByteArray {
        require(seed.size == 32 && requireNotNull(PqNative.publicKey(algorithm.id, seed)).contentEquals(publicKey)) { "PQ backup key mismatch" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val header = magic + byteArrayOf(algorithm.id.toByte()) + ByteBuffer.allocate(4).putInt(network).array() +
            MessageDigest.getInstance("SHA-256").digest(publicKey) + salt
        val derived = key(password, salt)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derived, "AES"))
            require(cipher.iv.size == 12)
            cipher.updateAAD(header)
            header + cipher.iv + cipher.doFinal(seed)
        } finally { derived.fill(0) }
    }
    fun decrypt(record: ByteArray, algorithm: PqAlgorithm, network: Int, password: CharArray): ByteArray {
        require(record.size == 121 && record.copyOfRange(0, 8).contentEquals(magic) && record[8].toInt() == algorithm.id &&
                ByteBuffer.wrap(record, 9, 4).int == network) { "PQ backup profile/network mismatch" }
        val derived = key(password, record.copyOfRange(45, 61))
        val seed = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(derived, "AES"), GCMParameterSpec(128, record.copyOfRange(61, 73)))
            cipher.updateAAD(record.copyOfRange(0, 61))
            cipher.doFinal(record, 73, 48)
        } finally { derived.fill(0) }
        try {
            val publicKey = requireNotNull(PqNative.publicKey(algorithm.id, seed))
            require(MessageDigest.getInstance("SHA-256").digest(publicKey).contentEquals(record.copyOfRange(13, 45))) { "PQ backup identity mismatch" }
            return seed
        } catch (e: Throwable) { seed.fill(0); throw e }
    }
}
