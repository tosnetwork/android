package network.tos.wallet.data.account.pq

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.UUID

/** Restricted migration signing envelope, not proof of signature validity or durable storage.
 * The service must validate the typed binding/signature and persist SIGNING before using a key.
 * No reset, re-sign, dispatch or unlock transition exists, including after a process restart. */
internal class QuantumMigrationSigningRecord private constructor(
    val operation: UUID, binding: ByteArray, val phase: Phase, signature: ByteArray,
) {
    enum class Phase { RESERVED, SIGNING, SIGNED }
    private val bound = binding.copyOf()
    private val signed = signature.copyOf()
    val binding: ByteArray get() = bound.copyOf()
    val signatureDigest: ByteArray? get() = if (phase == Phase.SIGNED)
        MessageDigest.getInstance("SHA-256").digest(signed) else null

    init {
        require(operation != UUID(0, 0))
        require(bound.size in 1..MAX_BINDING_BYTES) { "Migration binding exceeds signing budget" }
        require(if (phase == Phase.SIGNED) signed.size == SIGNATURE_BYTES else signed.isEmpty())
    }
    fun beginSigning(): QuantumMigrationSigningRecord {
        check(phase == Phase.RESERVED) { "Migration signing already started or uncertain" }
        return QuantumMigrationSigningRecord(operation, bound, Phase.SIGNING, ByteArray(0))
    }
    /** A syntactically sized signature is not authenticated by this envelope layer. */
    fun finishSigning(signature: ByteArray): QuantumMigrationSigningRecord {
        check(phase == Phase.SIGNING) { "Migration signature cannot replace an existing result" }
        return QuantumMigrationSigningRecord(operation, bound, Phase.SIGNED, signature)
    }
    fun signature(): ByteArray {
        check(phase == Phase.SIGNED)
        return signed.copyOf()
    }
    fun encode(): ByteArray = ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).use { out ->
            out.writeInt(MAGIC)
            out.writeLong(operation.mostSignificantBits); out.writeLong(operation.leastSignificantBits)
            out.writeByte(phase.ordinal)
            out.writeInt(bound.size); out.write(bound)
            out.writeInt(signed.size); out.write(signed)
        }
    }.toByteArray().also { require(it.size <= MAX_SIGNING_BYTES) }

    companion object {
        private const val MAGIC = 0x514d5331 // QMS1
        const val MAX_FILE_BYTES = 262144
        const val SIGNATURE_BYTES = 7856
        private const val ENVELOPE_BYTES = 29
        // Leave bounded room for the later fee/dispatch/outcome envelope. Those layers must
        // enforce their own exact budget before signing; this reserve grants no dispatch.
        const val RESERVED_METADATA_BYTES = 512
        const val MAX_SIGNING_BYTES = MAX_FILE_BYTES - RESERVED_METADATA_BYTES
        const val MAX_BINDING_BYTES = MAX_SIGNING_BYTES - ENVELOPE_BYTES - SIGNATURE_BYTES

        fun reserve(binding: ByteArray): QuantumMigrationSigningRecord =
            QuantumMigrationSigningRecord(UUID.randomUUID(), binding, Phase.RESERVED, ByteArray(0))

        fun decode(encoded: ByteArray): QuantumMigrationSigningRecord {
            require(encoded.size in (ENVELOPE_BYTES + 1)..MAX_SIGNING_BYTES)
            return DataInputStream(ByteArrayInputStream(encoded)).use { input ->
                require(input.readInt() == MAGIC)
                val operation = UUID(input.readLong(), input.readLong())
                val phase = Phase.entries.getOrNull(input.readUnsignedByte()) ?: error("Unknown migration signing phase")
                val size = input.readInt()
                require(size in 1..MAX_BINDING_BYTES && size <= input.available() - 4)
                val binding = ByteArray(size).also(input::readFully)
                val count = input.readInt()
                require(count == if (phase == Phase.SIGNED) SIGNATURE_BYTES else 0)
                require(count == input.available())
                val signature = ByteArray(count).also(input::readFully)
                QuantumMigrationSigningRecord(operation, binding, phase, signature)
            }
        }
    }
}
