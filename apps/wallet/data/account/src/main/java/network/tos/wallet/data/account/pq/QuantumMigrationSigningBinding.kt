package network.tos.wallet.data.account.pq

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

/** Bounded public snapshot framing only. Decoding never authenticates a request or a chain state. */
internal class QuantumMigrationSigningBinding private constructor(
    val walletId: UUID, val fundingOperation: UUID, backup: ByteArray, fields: List<ByteArray>,
) {
    private val backupBytes: ByteArray
    private val payloads: List<ByteArray>
    val backupDigest: ByteArray get() = backupBytes.copyOf()
    val funding: ByteArray get() = payloads[0].copyOf()
    val prior: ByteArray get() = payloads[1].copyOf()
    val probe: ByteArray get() = payloads[2].copyOf()
    val request: ByteArray get() = payloads[3].copyOf()
    init {
        require(walletId != UUID(0, 0) && fundingOperation != UUID(0, 0) && backup.size == 32)
        require(fields.size == 4 && fields.all { it.size in 1..MAX_FIELD_BYTES })
        require(HEADER_BYTES.toLong() + fields.sumOf { it.size.toLong() } <= QuantumMigrationSigningRecord.MAX_BINDING_BYTES)
        backupBytes = backup.copyOf(); payloads = fields.map { it.copyOf() }
    }
    fun requireIdentical(other: QuantumMigrationSigningBinding) {
        check(encode().contentEquals(other.encode())) { "Migration signing binding changed" }
    }
    fun requireContext(wallet: UUID, operation: UUID, backup: ByteArray,
        funding: ByteArray, prior: ByteArray, probe: ByteArray,
    ) {
        check(walletId == wallet && fundingOperation == operation && backupBytes.contentEquals(backup) &&
            payloads[0].contentEquals(funding) && payloads[1].contentEquals(prior) && payloads[2].contentEquals(probe)) {
            "Migration recovery context changed"
        }
    }
    fun encode(): ByteArray = ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).use { out ->
            out.writeInt(MAGIC)
            for (id in listOf(walletId, fundingOperation)) { out.writeLong(id.mostSignificantBits); out.writeLong(id.leastSignificantBits) }
            out.write(backupBytes)
            for (bytes in payloads) { out.writeInt(bytes.size); out.write(bytes) }
        }
    }.toByteArray().also { require(it.size <= QuantumMigrationSigningRecord.MAX_BINDING_BYTES) }

    companion object {
        private const val MAGIC = 0x514d4231 // QMB1
        const val HEADER_BYTES = 84
        const val MAX_FIELD_BYTES = QuantumMigrationSigningRecord.MAX_BINDING_BYTES - HEADER_BYTES - 3
        fun pack(wallet: UUID, operation: UUID, backup: ByteArray,
            funding: ByteArray, prior: ByteArray, probe: ByteArray, request: ByteArray,
        ) = QuantumMigrationSigningBinding(wallet, operation, backup, listOf(funding, prior, probe, request))

        fun decode(encoded: ByteArray): QuantumMigrationSigningBinding {
            require(encoded.size in (HEADER_BYTES + 4)..QuantumMigrationSigningRecord.MAX_BINDING_BYTES)
            return DataInputStream(ByteArrayInputStream(encoded.copyOf())).use { input ->
                require(input.readInt() == MAGIC)
                fun uuid() = UUID(input.readLong(), input.readLong())
                val wallet = uuid(); val operation = uuid(); val backup = ByteArray(32).also(input::readFully)
                val fields = List(4) { index ->
                    val size = input.readInt()
                    require(size in 1..MAX_FIELD_BYTES && size <= input.available() - (3 - index) * 4)
                    ByteArray(size).also(input::readFully)
                }
                require(input.read() == -1)
                QuantumMigrationSigningBinding(wallet, operation, backup, fields)
            }
        }
    }
}
