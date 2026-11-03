package network.tos.security.privacy

internal object PrivacyNativeBridge {
    init { System.loadLibrary("tos_mobile_shielded") }
    external fun descriptor(seed: ByteArray, domain: ByteArray, index: Long): ByteArray?
    external fun scan(seed: ByteArray, request: ByteArray): ByteArray?
}

/** Synchronous wallet crypto only. Call off the UI thread with authenticated pool data.
 * Seed is the 32-byte TOS mnemonic master before role-specific Quantum derivation.
 * Neither a successful scan nor descriptor parsing verifies chain state.
 */
object PrivacyNative {
    private val lock = Any()
    const val MAX_REQUEST_BYTES = 2 * 1024 * 1024
    const val MAX_RESPONSE_BYTES = 1024 * 1024

    /** The caller must durably reserve index before showing the resulting descriptor. */
    fun descriptorAndWipeSeed(seed: ByteArray, domain: ByteArray, index: ULong): ByteArray {
        try {
            require(seed.size == 32 && domain.size == 32)
            return synchronized(lock) {
                PrivacyNativeBridge.descriptor(seed, domain, index.toLong())
                    ?.also { check(it.size == 2573) }
                    ?: error("Privacy descriptor generation failed")
            }
        } finally { seed.fill(0) }
    }

    /** The response contains private note material; encrypt retained state and never log it. */
    fun scanAndWipeSeed(seed: ByteArray, request: ByteArray): ByteArray {
        try {
            require(seed.size == 32 && request.isNotEmpty() && request.size <= MAX_REQUEST_BYTES)
            return synchronized(lock) {
                PrivacyNativeBridge.scan(seed, request)
                    ?.also { check(it.size in 1..MAX_RESPONSE_BYTES) }
                    ?: error("Privacy note scan failed")
            }
        } finally { seed.fill(0) }
    }
}
