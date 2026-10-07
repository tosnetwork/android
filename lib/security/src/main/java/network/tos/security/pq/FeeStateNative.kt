package network.tos.security.pq

internal object FeeStateNative {
    init { System.loadLibrary("tos_mobile_pq") }
    external fun open(path: ByteArray, globalId: Int, network: ByteArray, vault: ByteArray, treeId: ByteArray, epoch0: Long, time: Long): LongArray
    external fun preview(handle: Long, time: Long, chainNext: Long): LongArray
    external fun reserve(handle: Long, time: Long, chainNext: Long, leaf: Long, digest: ByteArray): LongArray
    external fun cache(handle: Long, token: Long, key: ByteArray, signature: ByteArray): Int
    external fun cached(handle: Long, leaf: Long, digest: ByteArray, key: ByteArray): ByteArray?
    external fun signOnce(handle: Long, time: Long, next: Long, leaf: Long, digest: ByteArray, key: ByteArray, seed: ByteArray, path: ByteArray): ByteArray?
    external fun close(handle: Long): Int
}

class FeeStateException(val status: Int) : IllegalStateException("Fee state operation refused: $status")

/** Local single-writer state only. Observations require verified chain proofs; preview does not authorize signing. */
class V5R2FeeState private constructor(private var handle: Long) : AutoCloseable {
    @Synchronized fun preview(time: Long, chainNext: Long): Long = value(FeeStateNative.preview(active(), time, chainNext))
    @Synchronized fun reserve(time: Long, chainNext: Long, leaf: Long, digest: ByteArray): Long {
        require(digest.size == 32)
        return value(FeeStateNative.reserve(active(), time, chainNext, leaf, digest))
    }
    /** The key must come from an authenticated enrollment; this does not approve broadcast. */
    @Synchronized fun cacheVerified(token: Long, key: ByteArray, signature: ByteArray) {
        require(key.size == 60 && signature.size == 2832)
        val status = FeeStateNative.cache(active(), token, key, signature)
        if (status != 0) throw FeeStateException(status)
    }
    @Synchronized fun cachedVerified(leaf: Long, digest: ByteArray, key: ByteArray): ByteArray {
        require(digest.size == 32 && key.size == 60)
        return FeeStateNative.cached(active(), leaf, digest, key) ?: throw FeeStateException(-2)
    }
    /** Consumes seed. Authenticated enrollment/time/route and broadcast freshness remain caller requirements. */
    @Synchronized fun signOnceAndWipe(time: Long, chainNext: Long, leaf: Long, digest: ByteArray,
                                     publicKey: ByteArray, seed: ByteArray, path: ByteArray): ByteArray {
        try {
            require(digest.size == 32 && publicKey.size == 60 && seed.size == 48 && path.size == 640)
            return FeeStateNative.signOnce(active(), time, chainNext, leaf, digest, publicKey, seed, path)
                ?: throw FeeStateException(-2)
        } finally { seed.fill(0) }
    }
    @Synchronized override fun close() {
        if (handle == 0L) return
        val status = FeeStateNative.close(handle)
        if (status != 0) throw FeeStateException(status)
        handle = 0
    }
    private fun active(): Long { check(handle != 0L) { "Fee state session closed" }; return handle }
    companion object {
        fun open(directory: java.io.File, globalId: Int, network: ByteArray, vault: ByteArray, treeId: ByteArray, epoch0: Long, provenTime: Long): V5R2FeeState {
            require(directory.isAbsolute && network.size == 32 && vault.size == 32 && treeId.size == 32)
            return V5R2FeeState(value(FeeStateNative.open(directory.path.toByteArray(Charsets.UTF_8), globalId, network, vault, treeId, epoch0, provenTime)))
        }
        private fun value(result: LongArray): Long {
            check(result.size == 2)
            if (result[0] != 0L) throw FeeStateException(result[0].toInt())
            return result[1]
        }
    }
}
