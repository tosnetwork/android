package network.tos.security.pq

/** Bounded reads over authenticated plaintext; callers still validate all field semantics. */
internal class QuantumBackupReader(private val bytes: ByteArray) {
    private var offset = 0

    init {
        require(bytes.size in 1..QuantumBackupEnvelope.MAX_CIPHERTEXT_SIZE) { "Invalid backup plaintext size" }
    }

    fun unsignedByte(): Int {
        requireAvailable(1)
        return bytes[offset++].toInt() and 255
    }

    fun unsignedShort(): Int = (unsignedByte() shl 8) or unsignedByte()

    fun unsignedInt(): Long = (0 until 4).fold(0L) { value, _ ->
        (value shl 8) or unsignedByte().toLong()
    }

    fun fixed(count: Int): ByteArray {
        requireAvailable(count)
        return bytes.copyOfRange(offset, offset + count).also { offset += count }
    }

    fun boundedBlob(maximum: Int): ByteArray {
        require(maximum in 1..QuantumBackupEnvelope.MAX_CIPHERTEXT_SIZE)
        val count = unsignedInt()
        require(count in 1L..maximum.toLong()) { "Invalid backup field length" }
        return fixed(count.toInt())
    }

    fun requireEnd() {
        require(offset == bytes.size) { "Trailing backup plaintext" }
    }

    private fun requireAvailable(count: Int) {
        require(count >= 0 && count <= bytes.size - offset) { "Truncated backup plaintext" }
    }
}
