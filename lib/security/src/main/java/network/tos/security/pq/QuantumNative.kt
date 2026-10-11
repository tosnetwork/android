package network.tos.security.pq

import java.security.SecureRandom

enum class QuantumRole(val id: Int, val seedSize: Int, val publicKeySize: Int, val signatureSize: Int, val entropySize: Int) {
    PRIMARY(1, 32, 1312, 2420, 32), RESCUE(2, 48, 32, 7856, 16)
}
enum class QuantumPurpose(val id: Int) { AUTH(1), POP(2), PREPARATION(3) }
internal object QuantumNative {
    init { System.loadLibrary("tos_mobile_pq") }
    external fun publicKey(role: Int, seed: ByteArray): ByteArray?
    external fun sign(role: Int, purpose: Int, seed: ByteArray, entropy: ByteArray, digest: ByteArray): ByteArray?
    external fun verify(role: Int, purpose: Int, publicKey: ByteArray, digest: ByteArray, signature: ByteArray): Boolean
}
/** Stateless PQ custody primitive. Live proofs and LMS journals belong to the wallet service. */
object QuantumCrypto {
    fun publicKey(role: QuantumRole, seed: ByteArray): ByteArray {
        require(seed.size == role.seedSize) { "Incorrect Quantum seed size" }
        return checkNotNull(QuantumNative.publicKey(role.id, seed)).also { check(it.size == role.publicKeySize) }
    }
    fun sign(role: QuantumRole, purpose: QuantumPurpose, seed: ByteArray, expectedPublicKey: ByteArray, digest: ByteArray): ByteArray {
        require(digest.size == 32 && !(role == QuantumRole.PRIMARY && purpose == QuantumPurpose.PREPARATION))
        require(publicKey(role, seed).contentEquals(expectedPublicKey)) { "Quantum custody key mismatch" }
        val entropy = ByteArray(role.entropySize)
        try {
            SecureRandom().nextBytes(entropy)
            val signature = checkNotNull(QuantumNative.sign(role.id, purpose.id, seed, entropy, digest))
            check(signature.size == role.signatureSize && verify(role, purpose, expectedPublicKey, digest, signature))
            return signature
        } finally { entropy.fill(0) }
    }
    fun verify(role: QuantumRole, purpose: QuantumPurpose, publicKey: ByteArray, digest: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != role.publicKeySize || digest.size != 32 || signature.size != role.signatureSize) return false
        return QuantumNative.verify(role.id, purpose.id, publicKey, digest, signature)
    }
}
