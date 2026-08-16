package network.tos.agentcommerce

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.UUID

data class PurchaseJournalRecord(
    val purchaseId: String,
    val phase: String,
    val fundingLeaseId: String?,
    val updatedAtUnix: ULong,
)

class PurchaseJournalException(message: String) : Exception(message)

/**
 * Private atomic storage for one purchase. A funding broadcaster may run only
 * after [acquireFundingLease] has returned, which means a process restart sees
 * `funding_lease` and enters reconciliation instead of paying a second time.
 */
class PurchaseJournal(private val directory: File) {
    private val file = File(directory, "purchase-journal.v1")
    private val lockFile = File(directory, "purchase-journal.lock")
    private val monitor = Any()

    init {
        if (directory.exists()) {
            if (!directory.isDirectory) throw PurchaseJournalException("journal path is not a directory")
        } else if (!directory.mkdir()) {
            throw PurchaseJournalException("could not create journal directory")
        }
        makeOwnerOnly(directory, executable = true)
    }

    fun create(purchaseId: String, nowUnix: ULong): PurchaseJournalRecord = synchronized(monitor) { withProcessLock {
        if (file.exists()) throw PurchaseJournalException("journal already exists")
        val record = PurchaseJournalRecord(purchaseId, "intent", null, nowUnix)
        persistValidated(record)
        record
    } }

    fun load(): PurchaseJournalRecord = synchronized(monitor) { withProcessLock { loadUnlocked() } }

    fun advance(to: String, nowUnix: ULong): PurchaseJournalRecord = synchronized(monitor) { withProcessLock {
        val current = loadUnlocked()
        val crossesLease = PurchasePhase.order(current.phase) < PurchasePhase.order("funding_lease") &&
            PurchasePhase.order(to) >= PurchasePhase.order("funding_lease")
        if (to == "funding_lease" || !PurchasePhase.canAdvance(current.phase, to) || crossesLease) {
            throw PurchaseJournalException("illegal purchase transition")
        }
        val next = current.copy(phase = to, updatedAtUnix = nowUnix)
        persistValidated(next)
        next
    } }

    /** Persists the unique funding lease before permission to broadcast exists. */
    fun acquireFundingLease(id: String, nowUnix: ULong): PurchaseJournalRecord = synchronized(monitor) { withProcessLock {
        val current = loadUnlocked()
        if (!validIdentifier(id) || current.fundingLeaseId != null ||
            !PurchasePhase.canAcquireFundingLease(current.phase)
        ) {
            throw PurchaseJournalException("funding lease is unavailable")
        }
        val next = current.copy(phase = "funding_lease", fundingLeaseId = id, updatedAtUnix = nowUnix)
        persistValidated(next)
        next
    } }

    fun resumeAction(): ResumeAction = PurchasePhase.resumeActionFor(load().phase)

    private fun loadUnlocked(): PurchaseJournalRecord {
        if (!file.isFile || file.length() !in 1..16_384) {
            throw PurchaseJournalException("journal is missing or invalid")
        }
        val raw = file.readBytes()
        val text = raw.toString(Charsets.UTF_8)
        if (!text.endsWith('\n')) throw PurchaseJournalException("journal is truncated")
        val lines = text.dropLast(1).split('\n')
        if (lines.size != 5 || lines[0] != SCHEMA) throw PurchaseJournalException("journal schema is invalid")
        fun field(index: Int, name: String): String {
            val prefix = "$name="
            if (!lines[index].startsWith(prefix)) throw PurchaseJournalException("journal field is invalid")
            return lines[index].substring(prefix.length)
        }
        val record = PurchaseJournalRecord(
            purchaseId = field(1, "purchase_id"),
            phase = field(2, "phase"),
            fundingLeaseId = field(3, "funding_lease_id").ifEmpty { null },
            updatedAtUnix = field(4, "updated_at_unix").toULongOrNull()
                ?: throw PurchaseJournalException("journal timestamp is invalid"),
        )
        if (!isValid(record)) throw PurchaseJournalException("journal record is invalid")
        return record
    }

    private fun persistValidated(record: PurchaseJournalRecord) {
        if (!isValid(record)) throw PurchaseJournalException("journal record is invalid")
        val encoded = buildString {
            append(SCHEMA).append('\n')
            append("purchase_id=").append(record.purchaseId).append('\n')
            append("phase=").append(record.phase).append('\n')
            append("funding_lease_id=").append(record.fundingLeaseId.orEmpty()).append('\n')
            append("updated_at_unix=").append(record.updatedAtUnix).append('\n')
        }.toByteArray(Charsets.UTF_8)
        val temporary = File(directory, ".purchase-journal-${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary, false).use { output ->
                output.write(encoded)
                output.flush()
                output.fd.sync()
            }
            makeOwnerOnly(temporary, executable = false)
            if (!temporary.renameTo(file)) throw PurchaseJournalException("atomic journal rename failed")
            makeOwnerOnly(file, executable = false)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun <T> withProcessLock(action: () -> T): T = synchronized(processMonitor) {
        RandomAccessFile(lockFile, "rw").use { randomAccess ->
            makeOwnerOnly(lockFile, executable = false)
            randomAccess.channel.lock().use { action() }
        }
    }

    private fun isValid(record: PurchaseJournalRecord): Boolean {
        val position = PurchasePhase.order(record.phase)
        return validIdentifier(record.purchaseId) && position >= 0 &&
            ((position < PurchasePhase.order("funding_lease") && record.fundingLeaseId == null) ||
                (position >= PurchasePhase.order("funding_lease") &&
                    record.fundingLeaseId?.let(::validIdentifier) == true))
    }

    private fun makeOwnerOnly(path: File, executable: Boolean) {
        if (!path.setReadable(false, false) || !path.setWritable(false, false) ||
            !path.setExecutable(false, false) || !path.setReadable(true, true) ||
            !path.setWritable(true, true) || (executable && !path.setExecutable(true, true))
        ) {
            throw PurchaseJournalException("could not enforce private journal permissions")
        }
    }

    private fun validIdentifier(value: String): Boolean =
        value.isNotEmpty() && value.length <= 128 && value.all {
            it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_'
        }

    private companion object {
        const val SCHEMA = "tos.service.mobile-purchase-journal.v1"
        val processMonitor = Any()
    }
}
