package network.tos.security.pq

import java.nio.ByteBuffer

/** Structural checks only. This does not authenticate a backup or authorize import. */
internal object QuantumBackupEnvelope {
    const val HEADER_SIZE = 41
    const val TAG_SIZE = 16
    const val MAX_CIPHERTEXT_SIZE = 262144
    private val magic = "TOSQBK01".toByteArray(Charsets.US_ASCII)

    /** Own the bytes so callers cannot change the authenticated header after validation. */
    class Parsed internal constructor(
        val header: ByteArray,
        val salt: ByteArray,
        val nonce: ByteArray,
        val ciphertextAndTag: ByteArray,
    )

    fun parse(record: ByteArray): Parsed {
        require(record.size in (HEADER_SIZE + 1 + TAG_SIZE)..
            (HEADER_SIZE + MAX_CIPHERTEXT_SIZE + TAG_SIZE)) { "Invalid Quantum backup size" }
        val bytes = record.copyOf()
        require(bytes.copyOfRange(0, 8).contentEquals(magic)) { "Invalid Quantum backup magic" }
        require(bytes[8].toInt() == 1) { "Unsupported Quantum backup crypto profile" }
        // Reject unsigned lengths above the bound, including high-bit values,
        // before allocating ciphertext buffers or performing password derivation.
        val length = ByteBuffer.wrap(bytes, 37, 4).int.toLong() and 0xffffffffL
        require(length in 1L..MAX_CIPHERTEXT_SIZE.toLong()) { "Invalid Quantum backup payload size" }
        require(bytes.size.toLong() == HEADER_SIZE + length + TAG_SIZE) { "Invalid Quantum backup framing" }
        return Parsed(
            bytes.copyOfRange(0, HEADER_SIZE),
            bytes.copyOfRange(9, 25),
            bytes.copyOfRange(25, 37),
            bytes.copyOfRange(HEADER_SIZE, bytes.size),
        )
    }
}
