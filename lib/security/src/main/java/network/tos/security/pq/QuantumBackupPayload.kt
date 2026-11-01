package network.tos.security.pq

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Wire structure only, never a signing context or an authenticated current-role capability. */
internal class QuantumBackupPayload private constructor(
    val globalId: Int,
    val rootHash: ByteArray,
    val fileHash: ByteArray,
    val namespace: ByteArray,
    val birthBoc: ByteArray,
    val authorityBoc: ByteArray,
    val roles: List<Role>,
) : AutoCloseable {
    class Role internal constructor(
        val role: Int, val account: Long, val generation: Long,
        val seed: ByteArray, val publicKeyHash: ByteArray,
        val sourceProfile: Int, val wordIndices: IntArray,
        val phrasePassword: ByteArray, val master: ByteArray,
    ) {
        internal fun wipe() { seed.fill(0); wordIndices.fill(0); phrasePassword.fill(0); master.fill(0) }
    }

    override fun close() { roles.forEach { it.wipe() } }

    companion object {
        /** Caller must wipe the authenticated plaintext input after this returns or throws. */
        fun parseUnverified(plaintext: ByteArray): QuantumBackupPayload {
            val reader = QuantumBackupReader(plaintext)
            val roles = mutableListOf<Role>()
            try {
                require(reader.unsignedByte() == 1) { "Unsupported backup payload version" }
                val globalId = reader.unsignedInt().toInt()
                val root = reader.fixed(32)
                val file = reader.fixed(32)
                val namespace = reader.fixed(32)
                val birth = reader.boundedBlob(65536)
                val authority = reader.boundedBlob(16384)
                val count = reader.unsignedByte()
                require(count in 1..2) { "Invalid backup role count" }
                var previous = 0
                repeat(count) {
                    val role = reader.unsignedByte()
                    require(role in 1..2 && role > previous) { "Invalid backup role order" }
                    previous = role
                    val account = reader.unsignedInt()
                    val generation = reader.unsignedInt()
                    val seed = reader.fixed(if (role == 1) 32 else 48)
                    var password = ByteArray(0)
                    var master = ByteArray(0)
                    var words = IntArray(0)
                    try {
                        val keyHash = reader.fixed(32)
                        val source = reader.unsignedByte()
                        when (source) {
                            1 -> Unit
                            2 -> {
                                require(reader.unsignedByte() == 1) { "Unsupported backup wordlist" }
                                val wordCount = reader.unsignedByte()
                                require(wordCount == 12 || wordCount == 24) { "Invalid backup word count" }
                                words = IntArray(wordCount)
                                repeat(wordCount) { index ->
                                    val word = reader.unsignedShort()
                                    require(word < 2048) { "Invalid backup word index" }
                                    words[index] = word
                                }
                                val length = reader.unsignedShort()
                                require(length <= 1024) { "Invalid phrase password size" }
                                password = reader.fixed(length)
                                val decoded = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                                    .decode(ByteBuffer.wrap(password))
                                if (decoded.hasArray()) decoded.array().fill('\u0000')
                            }
                            3 -> { master = reader.fixed(32) }
                            else -> throw IllegalArgumentException("Unsupported backup source profile")
                        }
                        roles += Role(role, account, generation, seed, keyHash, source, words, password, master)
                    } catch (error: Throwable) {
                        seed.fill(0); words.fill(0); password.fill(0); master.fill(0)
                        throw error
                    }
                }
                reader.requireEnd()
                return QuantumBackupPayload(globalId, root, file, namespace, birth, authority, roles)
            } catch (error: Throwable) {
                roles.forEach { it.wipe() }
                throw error
            }
        }
    }
}
