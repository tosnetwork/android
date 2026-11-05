package network.tos.security.privacy

/** Cleanup/publication boundary shared by the synchronous summary readers. No balance is computed here. */
internal object PrivacySummaryRead {
    fun readAndWipeSeed(seed: ByteArray, checkCurrent: () -> Unit, read: () -> ByteArray): ByteArray {
        var response: ByteArray? = null
        var published = false
        try {
            require(seed.size == 32)
            checkCurrent()
            val bytes = read().also { response = it }
            check(bytes.size in 1..1024 * 1024) { "Invalid privacy summary response size" }
            checkCurrent()
            published = true
            return bytes
        } finally {
            seed.fill(0)
            if (!published) response?.fill(0)
        }
    }
}
