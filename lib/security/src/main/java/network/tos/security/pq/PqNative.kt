package network.tos.security.pq

/** Exact TOS AUTH profiles; native expanded secrets are wiped after every call. */
enum class PqAlgorithm(val id: Int, val publicKeySize: Int, val signatureSize: Int, val minimumVm: Int) {
    MLDSA44(1, 1312, 2420, 16), FALCON512_PADDED(2, 897, 666, 19)
}
internal object PqNative {
    init { System.loadLibrary("tos_mobile_pq") }
    external fun publicKey(algorithm: Int, seed: ByteArray): ByteArray?
    external fun sign(algorithm: Int, seed: ByteArray, entropy: ByteArray, message: ByteArray): ByteArray?
    external fun verify(algorithm: Int, publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean
}
object PqVerifier {
    fun verify(algorithm: PqAlgorithm, publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
        PqNative.verify(algorithm.id, publicKey, message, signature)
}
