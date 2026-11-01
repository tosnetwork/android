package network.tos.security.pq

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import network.tos.blockchain.TosQuantumMnemonic
import org.ton.mnemonic.Mnemonic

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

    /** Snapshot identity, provenance and fresh chain-role checks are still required separately. */
    fun validateSnapshotKeys(primary: ByteArray, rescue: ByteArray) {
        require(primary.size == QuantumRole.PRIMARY.publicKeySize && rescue.size == QuantumRole.RESCUE.publicKeySize)
        for (entry in roles) {
            val role = when (entry.role) {
                1 -> QuantumRole.PRIMARY
                2 -> QuantumRole.RESCUE
                else -> throw IllegalArgumentException("Unsupported backup role")
            }
            val derived = QuantumCrypto.publicKey(role, entry.seed)
            val expected = if (role == QuantumRole.PRIMARY) primary else rescue
            require(MessageDigest.isEqual(derived, expected) &&
                MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(derived), entry.publicKeyHash)) {
                "Backup seed does not match snapshot key"
            }
        }
    }

    /** Source-to-seed mapping only, never a live authority or signing capability. */
    fun validateProvenance() {
        for (entry in roles) {
            if (entry.sourceProfile == 1) continue
            var master = ByteArray(0)
            var derived = ByteArray(0)
            try {
                master = when (entry.sourceProfile) {
                    2 -> {
                        val dictionary = Mnemonic.mnemonicWords()
                        require(dictionary.size == 2048 && entry.wordIndices.all { it in dictionary.indices })
                        val words = entry.wordIndices.map { dictionary[it] }
                        val decoded = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(entry.phrasePassword))
                        val password = CharArray(decoded.remaining())
                        try {
                            decoded.get(password)
                            TosQuantumMnemonic.masterAndWipePassword(words, password)
                        } finally {
                            password.fill('\u0000')
                            if (decoded.hasArray()) decoded.array().fill('\u0000')
                        }
                    }
                    3 -> entry.master.copyOf()
                    else -> throw IllegalArgumentException("Unsupported backup source profile")
                }
                val material = when (entry.role) {
                    1 -> QuantumKdf.Material.PRIMARY
                    2 -> QuantumKdf.Material.RESCUE
                    else -> throw IllegalArgumentException("Unsupported backup role")
                }
                derived = QuantumKdf.deriveAndWipe(material, master, namespace, globalId,
                    entry.account, entry.generation)
                require(MessageDigest.isEqual(derived, entry.seed)) { "Backup provenance does not match seed" }
            } finally {
                master.fill(0); derived.fill(0)
            }
        }
    }

    companion object {
        /** Wire encoding only. The custody layer must first verify identity and role provenance. */
        fun encodeUnverified(
            globalId: Int, rootHash: ByteArray, fileHash: ByteArray, namespace: ByteArray,
            birthBoc: ByteArray, authorityBoc: ByteArray, roles: List<Role>,
        ): ByteArray {
            require(rootHash.size == 32 && fileHash.size == 32 && namespace.size == 32)
            require(birthBoc.size in 1..65536 && authorityBoc.size in 1..16384)
            require(roles.size in 1..2)
            var size = 1 + 4 + 96 + 4 + birthBoc.size + 4 + authorityBoc.size + 1
            var previous = 0
            for (role in roles) {
                require(role.role in 1..2 && role.role > previous)
                previous = role.role
                require(role.account in 0L..0xffffffffL && role.generation in 0L..0xffffffffL)
                require(role.seed.size == if (role.role == 1) 32 else 48)
                require(role.publicKeyHash.size == 32)
                size += 1 + 8 + role.seed.size + 32 + 1
                when (role.sourceProfile) {
                    1 -> require(role.wordIndices.isEmpty() && role.phrasePassword.isEmpty() && role.master.isEmpty())
                    2 -> {
                        require(role.wordIndices.size == 12 || role.wordIndices.size == 24)
                        require(role.wordIndices.all { it in 0..2047 })
                        require(role.phrasePassword.size <= 1024 && role.master.isEmpty())
                        size += 2 + role.wordIndices.size * 2 + 2 + role.phrasePassword.size
                    }
                    3 -> require(role.master.size == 32 && role.wordIndices.isEmpty() && role.phrasePassword.isEmpty())
                    else -> throw IllegalArgumentException("Unsupported backup source profile")
                }
                if (role.sourceProfile == 3) size += 32
            }
            require(size <= QuantumBackupEnvelope.MAX_CIPHERTEXT_SIZE)
            val output = ByteArray(size)
            try {
                val buffer = ByteBuffer.wrap(output)
                buffer.put(1).putInt(globalId).put(rootHash).put(fileHash).put(namespace)
                buffer.putInt(birthBoc.size).put(birthBoc).putInt(authorityBoc.size).put(authorityBoc)
                buffer.put(roles.size.toByte())
                roles.forEach { role ->
                    buffer.put(role.role.toByte()).putInt(role.account.toInt()).putInt(role.generation.toInt())
                        .put(role.seed).put(role.publicKeyHash).put(role.sourceProfile.toByte())
                    when (role.sourceProfile) {
                        2 -> {
                            buffer.put(1).put(role.wordIndices.size.toByte())
                            role.wordIndices.forEach { buffer.putShort(it.toShort()) }
                            buffer.putShort(role.phrasePassword.size.toShort()).put(role.phrasePassword)
                        }
                        3 -> buffer.put(role.master)
                    }
                }
                check(!buffer.hasRemaining())
                // Share the decoder's strict UTF-8 and exact-wire rules. Erase its temporary secrets.
                parseUnverified(output).close()
                return output
            } catch (error: Throwable) {
                output.fill(0)
                throw error
            }
        }

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
