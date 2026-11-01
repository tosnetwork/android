package network.tos.wallet.data.account.pq

/** One birth-identity slot. All IO methods execute under the backend's process/file lock.
 * Typed proof/request/signature validation belongs to the service, not this storage protocol. */
internal class QuantumMigrationSigningStore(private val io: IO) {
    interface IO {
        fun <T> locked(body: () -> T): T
        fun hasPending(): Boolean
        fun readCurrent(): ByteArray?
        fun writePending(bytes: ByteArray)
        fun replacePending()
        fun syncCurrent()
        fun syncDirectory()
    }

    fun load(): QuantumMigrationSigningRecord? = io.locked { retained() }

    fun reserve(binding: ByteArray): QuantumMigrationSigningRecord = io.locked {
        check(retained() == null) { "Migration slot already occupied" }
        val record = QuantumMigrationSigningRecord.reserve(binding)
        publish(record)
        record
    }

    fun beginSigning(expected: QuantumMigrationSigningRecord): QuantumMigrationSigningRecord = io.locked {
        val actual = requireCurrent(expected)
        val next = actual.beginSigning()
        publish(next)
        next
    }

    /** Durable uncertainty precedes key use. Failures never reset the slot or retain a caller buffer. */
    fun signOnce(expected: QuantumMigrationSigningRecord, signer: () -> ByteArray,
        verify: (ByteArray) -> Unit): QuantumMigrationSigningRecord {
        val signing = beginSigning(expected)
        val signature = signer()
        try {
            verify(signature)
            return finishSigning(signing, signature)
        } finally { signature.fill(0) }
    }

    /** Caller verifies the exact signature first. No signature/key operation runs inside this store. */
    fun finishSigning(expected: QuantumMigrationSigningRecord, signature: ByteArray): QuantumMigrationSigningRecord = io.locked {
        val actual = requireCurrent(expected)
        val next = actual.finishSigning(signature)
        publish(next)
        next
    }

    /** Fresh native quote and explicit fee confirmation are checked by the account service. */
    fun beginSubmission(expected: QuantumMigrationSigningRecord, approval: ByteArray): QuantumMigrationSigningRecord = io.locked {
        val next = requireCurrent(expected).beginSubmission(approval)
        publish(next)
        next
    }

    fun unknown(expected: QuantumMigrationSigningRecord): QuantumMigrationSigningRecord = io.locked {
        val next = requireCurrent(expected).unknown()
        publish(next)
        next
    }

    private fun requireCurrent(expected: QuantumMigrationSigningRecord): QuantumMigrationSigningRecord {
        val actual = checkNotNull(retained()) { "Migration reservation missing" }
        check(actual.encode().contentEquals(expected.encode())) { "Migration reservation changed" }
        return actual
    }

    private fun retained(): QuantumMigrationSigningRecord? {
        check(!io.hasPending()) { "Migration persistence uncertain" }
        val bytes = io.readCurrent() ?: return null
        val record = QuantumMigrationSigningRecord.decode(bytes)
        // A previous rename/directory-sync failure may have left the new inode visible only
        // in this process. Retrying durability must succeed before exposing any usable record.
        io.syncCurrent()
        io.syncDirectory()
        check(!io.hasPending() && checkNotNull(io.readCurrent()).contentEquals(bytes)) { "Migration record changed during sync" }
        return record
    }

    private fun publish(record: QuantumMigrationSigningRecord) {
        check(!io.hasPending()) { "Migration persistence uncertain" }
        val bytes = record.encode()
        io.writePending(bytes) // Exclusive create, complete write and file sync.
        io.replacePending()
        io.syncDirectory()
        check(!io.hasPending() && checkNotNull(io.readCurrent()).contentEquals(bytes)) { "Migration write could not be verified" }
        // Failed writes deliberately retain both files; no cleanup/reset can reopen signing.
    }
}
