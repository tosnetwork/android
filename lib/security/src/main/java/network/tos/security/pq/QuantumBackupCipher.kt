package network.tos.security.pq

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.KeyParameter

/** Authenticated envelope only; decrypted bytes still require typed payload validation. */
internal object QuantumBackupCipher {
    private fun derive(password: CharArray, salt: ByteArray): ByteArray {
        val encoded = QuantumBackupPassword.encode(password)
        return try {
            val generator = PKCS5S2ParametersGenerator(SHA256Digest())
            generator.init(encoded, salt, 600000)
            (generator.generateDerivedParameters(256) as KeyParameter).key
        } finally { encoded.fill(0) }
    }

    fun encrypt(payload: ByteArray, password: CharArray): ByteArray {
        require(payload.size in 1..QuantumBackupEnvelope.MAX_CIPHERTEXT_SIZE)
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val header = "TOSQBK01".toByteArray(Charsets.US_ASCII) + byteArrayOf(1) +
            salt + nonce + ByteBuffer.allocate(4).putInt(payload.size).array()
        val key = derive(password, salt)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(header)
            header + cipher.doFinal(payload)
        } finally { key.fill(0) }
    }

    fun decrypt(record: ByteArray, password: CharArray): ByteArray {
        val envelope = QuantumBackupEnvelope.parse(record)
        val key = derive(password, envelope.salt)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, envelope.nonce))
            cipher.updateAAD(envelope.header)
            cipher.doFinal(envelope.ciphertextAndTag)
        } finally { key.fill(0) }
    }
}
