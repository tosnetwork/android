package network.tos.wallet.data.account.pq

/** Typed boundary over the durable single slot. No method grants signing or dispatch authority. */
internal class QuantumMigrationSigningJournal(private val store: QuantumMigrationSigningStore) {
    fun reserve(plan: QuantumPreparedSuccessorMigration, entry: QuantumProbeFundingJournal.Reservation,
        prior: QuantumRecoveryControlJournal.Entry, probe: QuantumRecoveryProbeJournal.Entry, now: Long,
    ): QuantumMigrationSigningRecord {
        val snapshot = QuantumMigrationSigningSnapshot.create(plan, entry, prior, probe, now)
        return store.reserve(snapshot.encode())
    }

    /** Reauthentication never resets SIGNING/SIGNED or replaces the saved request/deadline. */
    fun restore(entry: QuantumProbeFundingJournal.Reservation, prior: QuantumRecoveryControlJournal.Entry,
        probe: QuantumRecoveryProbeJournal.Entry, result: QuantumProbeFundingOutcomeReader.Result,
        confirmedBackupDigest: ByteArray, now: Long,
    ): Pair<QuantumMigrationSigningRecord, QuantumPreparedSuccessorMigration> {
        val record = checkNotNull(store.load()) { "Migration reservation missing" }
        val plan = QuantumMigrationSigningSnapshot.decode(record.binding).restore(
            entry, prior, probe, result, confirmedBackupDigest, now)
        // Detect another process advancing the slot during proof/request validation. Later
        // phase transitions must still CAS the returned exact record under the store lock.
        check(checkNotNull(store.load()).encode().contentEquals(record.encode())) {
            "Migration reservation changed during reauthentication"
        }
        return record to plan
    }
}
