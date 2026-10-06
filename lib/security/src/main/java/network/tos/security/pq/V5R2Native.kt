package network.tos.security.pq

import java.security.SecureRandom

enum class V5R2Role(val id: Int, val seedSize: Int, val publicKeySize: Int, val signatureSize: Int, val entropySize: Int) {
    PRIMARY(1, 32, 1312, 2420, 32), RESCUE(2, 48, 32, 7856, 16)
}
enum class V5R2Purpose(val id: Int) { AUTH(1), POP(2), PREPARATION(3) }
internal object V5R2Native {
    init { System.loadLibrary("tos_mobile_pq") }
    external fun publicKey(role: Int, seed: ByteArray): ByteArray?
    external fun sign(role: Int, purpose: Int, seed: ByteArray, entropy: ByteArray, digest: ByteArray): ByteArray?
    external fun verify(role: Int, purpose: Int, publicKey: ByteArray, digest: ByteArray, signature: ByteArray): Boolean
}
/** Stateless PQ custody primitive. Live proofs and LMS journals belong to the wallet service. */
object V5R2Crypto {
    fun publicKey(role: V5R2Role, seed: ByteArray): ByteArray {
        require(seed.size == role.seedSize) { "Incorrect V5R2 seed size" }
        return checkNotNull(V5R2Native.publicKey(role.id, seed)).also { check(it.size == role.publicKeySize) }
    }
    fun sign(role: V5R2Role, purpose: V5R2Purpose, seed: ByteArray, expectedPublicKey: ByteArray, digest: ByteArray): ByteArray {
        require(digest.size == 32 && !(role == V5R2Role.PRIMARY && purpose == V5R2Purpose.PREPARATION))
        require(publicKey(role, seed).contentEquals(expectedPublicKey)) { "V5R2 custody key mismatch" }
        val entropy = ByteArray(role.entropySize)
        try {
            SecureRandom().nextBytes(entropy)
            val signature = checkNotNull(V5R2Native.sign(role.id, purpose.id, seed, entropy, digest))
            check(signature.size == role.signatureSize && verify(role, purpose, expectedPublicKey, digest, signature))
            return signature
        } finally { entropy.fill(0) }
    }
    fun verify(role: V5R2Role, purpose: V5R2Purpose, publicKey: ByteArray, digest: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != role.publicKeySize || digest.size != 32 || signature.size != role.signatureSize) return false
        return V5R2Native.verify(role.id, purpose.id, publicKey, digest, signature)
    }
}
