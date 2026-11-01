package network.tos.wallet.data.account.pq

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.UUID

/** Restricted migration signing envelope, not proof of signature validity or durable storage.
 * The service must validate the typed binding/signature and persist SIGNING before using a key.
 * Submission phases retain uncertainty; this codec never dispatches or unlocks recovery. */
internal class QuantumMigrationSigningRecord private constructor(
    val operation: UUID, binding: ByteArray, val phase: Phase, signature: ByteArray,
    approval: ByteArray = ByteArray(0),
) {
    enum class Phase { RESERVED, SIGNING, SIGNED, SUBMITTING, UNKNOWN }
    private val bound = binding.copyOf()
    private val signed = signature.copyOf()
    private val approved = approval.copyOf()
    val binding: ByteArray get() = bound.copyOf()
    val signatureDigest: ByteArray? get() = if (phase in SIGNED_PHASES)
        MessageDigest.getInstance("SHA-256").digest(signed) else null

    init {
        require(operation != UUID(0, 0))
        require(bound.size in 1..MAX_BINDING_BYTES) { "Migration binding exceeds signing budget" }
        require(if (phase in SIGNED_PHASES) signed.size == SIGNATURE_BYTES else signed.isEmpty())
        if (phase in SUBMITTED_PHASES) require(QuantumMigrationApproval.decode(approved).operation == operation)
        else require(approved.isEmpty())
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
        check(phase in SIGNED_PHASES)
        return signed.copyOf()
    }
    fun beginSubmission(approval: ByteArray): QuantumMigrationSigningRecord {
        check(phase == Phase.SIGNED) { "Migration submission already started or uncertain" }
        return QuantumMigrationSigningRecord(operation, bound, Phase.SUBMITTING, signed, approval)
    }
    fun unknown(): QuantumMigrationSigningRecord {
        check(phase in SUBMITTED_PHASES)
        return QuantumMigrationSigningRecord(operation, bound, Phase.UNKNOWN, signed, approved)
    }
    fun approval(): ByteArray {
        check(phase in SUBMITTED_PHASES)
        return approved.copyOf()
    }
    fun encode(): ByteArray = ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).use { out ->
            out.writeInt(if (phase in SUBMITTED_PHASES) SUBMISSION_MAGIC else MAGIC)
            out.writeLong(operation.mostSignificantBits); out.writeLong(operation.leastSignificantBits)
            out.writeByte(phase.ordinal)
            out.writeInt(bound.size); out.write(bound)
            out.writeInt(signed.size); out.write(signed)
            if (phase in SUBMITTED_PHASES) { out.writeInt(approved.size); out.write(approved) }
        }
    }.toByteArray().also { require(it.size <= if (phase in SUBMITTED_PHASES) MAX_FILE_BYTES else MAX_SIGNING_BYTES) }

    companion object {
        private const val MAGIC = 0x514d5331 // QMS1
        private const val SUBMISSION_MAGIC = 0x514d5332 // QMS2
        private val SUBMITTED_PHASES = setOf(Phase.SUBMITTING, Phase.UNKNOWN)
        private val SIGNED_PHASES = setOf(Phase.SIGNED, Phase.SUBMITTING, Phase.UNKNOWN)
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
            require(encoded.size in (ENVELOPE_BYTES + 1)..MAX_FILE_BYTES)
            return DataInputStream(ByteArrayInputStream(encoded.copyOf())).use { input ->
                val magic = input.readInt()
                require(magic == MAGIC || magic == SUBMISSION_MAGIC)
                val operation = UUID(input.readLong(), input.readLong())
                val phase = Phase.entries.getOrNull(input.readUnsignedByte()) ?: error("Unknown migration signing phase")
                require(if (magic == MAGIC) phase !in SUBMITTED_PHASES && encoded.size <= MAX_SIGNING_BYTES
                    else phase in SUBMITTED_PHASES)
                val size = input.readInt()
                require(size in 1..MAX_BINDING_BYTES && size <= input.available() - 4)
                val binding = ByteArray(size).also(input::readFully)
                val count = input.readInt()
                require(count == if (phase in SIGNED_PHASES) SIGNATURE_BYTES else 0)
                require(count <= input.available())
                val signature = ByteArray(count).also(input::readFully)
                val approval = if (phase in SUBMITTED_PHASES) {
                    require(input.readInt() == QuantumMigrationApproval.SIZE && input.available() == QuantumMigrationApproval.SIZE)
                    ByteArray(QuantumMigrationApproval.SIZE).also(input::readFully)
                } else ByteArray(0)
                require(input.read() == -1)
                QuantumMigrationSigningRecord(operation, binding, phase, signature, approval)
            }
        }
    }
}
