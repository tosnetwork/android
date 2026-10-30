package network.tos.wallet.data.account.pq

import android.system.Os
import network.tos.blockchain.ton.contract.TosQuantumSelfPayIdentity
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

/** Local history is display data, never an expiry/receipt capability or permission to delete an active slot. */
class QuantumAuthorizationRetirement internal constructor(
    private val outbox: QuantumPaymentOutbox, fault: ((String) -> Unit)?,
) {
    enum class State { RELEASE_PENDING, RELEASE_COMPLETED }
    enum class Result { SENT, INCLUDED_WITHOUT_EXACT_TRANSFER, EXPIRED_UNVERIFIED }
    class Summary internal constructor(internal val decision: String, val state: State, val completedAt: Long?) {
        private val value = json(decision.toByteArray(Charsets.UTF_8), MAX_DECISION)
        val operationId: UUID = uuid(text(value, "operation"))
        val birthHash: String = hash(text(value, "birth_hash"))
        val result: Result = Result.valueOf(text(value, "result"))
        val reason: String? = if (value.isNull("reason")) null else text(value, "reason").also { check(it.matches(Regex("[a-z-]{1,80}"))) }
        val deadline: Long = time(value, "deadline")
        val provenShardTime: Long = time(value, "proven_shard_time").also { check(it > deadline) }
        val intent = QuantumPaymentOutbox.decodeIntent(value.getJSONObject("intent"))
        val transactionHash: String? = if (value.isNull("transaction_hash")) null else hash(text(value, "transaction_hash"))
        val logicalTime: String? = if (value.isNull("logical_time")) null else text(value, "logical_time").also {
            check(it.matches(Regex("[1-9][0-9]{0,19}")) && it.toULongOrNull() != null)
        }
        init {
            check(value.get("version") is Int && value.getInt("version") == 1)
            check((result == Result.EXPIRED_UNVERIFIED) == (transactionHash == null && logicalTime == null))
            check((result == Result.SENT) == (reason == null))
            if (result != Result.EXPIRED_UNVERIFIED) check(transactionHash != null && logicalTime != null)
            check((state == State.RELEASE_COMPLETED) == (completedAt != null))
            completedAt?.let { check(it in 1..0xffffffffL) }
            QuantumNetworkProfile.parse(value.getJSONObject("profile"))
            hash(text(value, "entry_sha256")); hash(text(value, "request_sha256")); hash(text(value, "request_hash"))
            hash(text(value, "authority_hash")); hash(text(value, "public_key_sha256"))
            for (key in listOf("epoch", "nonce")) check(text(value, key).let {
                it.matches(Regex("0|[1-9][0-9]{0,19}")) && it.toULongOrNull() != null
            })
            check(text(value, "role") == "PRIMARY")
            for (key in listOf("signature_sha256", "message_sha256")) if (!value.isNull(key)) hash(text(value, key))
            check(value.get("deploy") is Boolean)
            QuantumPaymentOutbox.Phase.valueOf(text(value, "phase"))
            // The verified target is retained for provenance; decoding it cannot mint a capability.
            check(value.getJSONObject("proof_target").length() in 1..8)
            check((result == Result.EXPIRED_UNVERIFIED) == value.isNull("receipt_target"))
        }
        internal fun bytes(): ByteArray = JSONObject().put("version", 1).put("state", state.name)
            .put("completed_at", completedAt ?: JSONObject.NULL).put("decision", decision).toString().toByteArray(Charsets.UTF_8)
            .also { check(it.size <= MAX_SUMMARY) }
        internal fun requireEntry(entry: QuantumPaymentOutbox.Entry, raw: ByteArray) {
            val original = entry.authorization()
            check(entry.id == operationId && birth(entry.identity) == birthHash && text(value, "entry_sha256") == digest(raw))
            check(sameProfile(entry.profile, QuantumNetworkProfile.parse(value.getJSONObject("profile"))) &&
                entry.deadline == deadline && entry.intent == intent && entry.deploy == value.getBoolean("deploy") &&
                entry.phase.name == text(value, "phase") && digest(entry.requestBOC) == text(value, "request_sha256") &&
                QuantumPaymentQuote.hex(entry.requestHash) == text(value, "request_hash"))
            check(original.role.name == text(value, "role") && original.epoch.toString() == text(value, "epoch") &&
                original.nonce.toString() == text(value, "nonce") && QuantumPaymentQuote.hex(original.authorityHash) == text(value, "authority_hash") &&
                digest(entry.signingPublicKey) == text(value, "public_key_sha256"))
            for ((name, bytes) in listOf("signature_sha256" to entry.signature, "message_sha256" to entry.signedBOC)) {
                check(if (bytes == null) value.isNull(name) else text(value, name) == digest(bytes))
            }
        }
        companion object {
            internal fun parse(bytes: ByteArray): Summary {
                val value = json(bytes, MAX_SUMMARY)
                check(value.keys().asSequence().toSet() == setOf("version", "state", "completed_at", "decision"))
                check(value.get("version") is Int && value.getInt("version") == 1)
                return Summary(text(value, "decision"), State.valueOf(text(value, "state")),
                    if (value.isNull("completed_at")) null else time(value, "completed_at"))
            }
        }
    }
    data class Status(val operationId: UUID?, val recovering: Boolean, val completedCleanupOnly: Boolean)
    private val io = QuantumRetirementStorage(fault)
    private val root get() = outbox.directory
    private val historyDirectory get() = File(root, ".retirement-history")
    private data class Work(val directory: File, val operation: UUID, val stage: Boolean)
    private fun workspace(): Work? {
        val works = outbox.names().filter { it.startsWith(".retiring-") }
        check(works.size <= 1) { "Multiple retirement workspaces require inspection" }
        return works.singleOrNull()?.let { name ->
            val stage = name.startsWith(".retiring-stage-")
            val id = uuid(name.removePrefix(if (stage) ".retiring-stage-" else ".retiring-"))
            val directory = File(root, name)
            check(io.names(directory, 3).all { it in setOf("entry.json", "decision.json") }) { "Unexpected retirement workspace content" }
            Work(directory, id, stage)
        }
    }
    private fun summaries(): List<Pair<File, Summary>> {
        if (!io.exists(historyDirectory)) return emptyList()
        val names = io.names(historyDirectory, 65)
        check(names.count { it.endsWith(".json") } <= MAX_HISTORY && names.count { it.endsWith(".tmp") } <= 1)
        return names.filter { !it.endsWith(".tmp") }.map { name ->
            check(name.endsWith(".json")); val id = uuid(name.removeSuffix(".json"))
            val file = File(historyDirectory, name)
            file to Summary.parse(io.read(file, MAX_SUMMARY)).also { check(it.operationId == id) }
        }
    }
    private fun summary(operation: UUID): Summary? = summaries().singleOrNull { it.second.operationId == operation }?.second
    private fun requireConsistentRecovery(work: Work?) {
        check(summaries().filter { it.second.state == State.RELEASE_PENDING }.all { it.second.operationId == work?.operation }) {
            "Release-pending record has no matching full retirement workspace"
        }
        if (io.exists(historyDirectory)) check(io.names(historyDirectory, 65).filter { it.endsWith(".tmp") }
            .all { work != null && it == "${work.operation}.json.tmp" }) { "Unrelated summary write requires inspection" }
    }
    internal fun requireIdle() {
        check(workspace() == null) { "Authorization retirement recovery required" }
        check(summaries().none { it.second.state == State.RELEASE_PENDING }) { "Authorization retirement recovery required" }
        if (io.exists(historyDirectory)) check(io.names(historyDirectory, 65).none { it.endsWith(".tmp") }) { "Retirement summary write requires recovery" }
    }
    internal fun status(identity: TosQuantumSelfPayIdentity): Status {
        val work = workspace()
        requireConsistentRecovery(work)
        if (work == null) {
            check(summaries().none { it.second.state == State.RELEASE_PENDING }) { "Missing retirement snapshot" }
            return Status(null, false, false)
        }
        val completed = summary(work.operation)?.takeIf { it.state == State.RELEASE_COMPLETED }
        if (completed != null) return Status(work.operation, true, true)
        val entry = snapshot(identity, work.operation)
        return Status(entry.id, true, false)
    }
    internal fun history(identity: TosQuantumSelfPayIdentity): List<Summary> = summaries().map { it.second }
        .filter { it.birthHash == birth(identity) }.sortedWith(compareByDescending<Summary> { it.completedAt ?: 0 }.thenBy { it.operationId.toString() })

    /** Validated inert full entry only; network authentication occurs outside the disk lock. */
    internal fun snapshot(identity: TosQuantumSelfPayIdentity, operation: UUID?): QuantumPaymentOutbox.Entry {
        val work = workspace()
        requireConsistentRecovery(work)
        val canonical = outbox.read(identity)
        if (work == null) return checkNotNull(canonical).also { if (operation != null) check(it.id == operation) }
        if (operation != null) check(work.operation == operation)
        val entryFile = File(work.directory, "entry.json")
        val decisionFile = File(work.directory, "decision.json")
        val rebuildAllowed = work.stage && !io.exists(decisionFile) && summary(work.operation) == null &&
            canonical != null && canonical.id == work.operation
        // Metadata reconstruction is limited to an unpublished retirement stage. Invalid permissions,
        // symlinks, extra filenames or budgets are never treated as a recoverable partial write.
        if (io.exists(entryFile)) io.read(entryFile, MAX_ENTRY, allowEmpty = rebuildAllowed)
        val parsed = if (rebuildAllowed) runCatching { outbox.readEntry(entryFile, identity) }.getOrNull()
            else outbox.readEntry(entryFile, identity)
        val entry = parsed ?: if (rebuildAllowed) checkNotNull(canonical) else error("Retirement full snapshot is unavailable")
        check(entry.id == work.operation)
        if (io.exists(decisionFile)) {
            val decision = decode(io.read(decisionFile, MAX_DECISION))
            Summary(decision, State.RELEASE_PENDING, null).requireEntry(entry, io.read(File(work.directory, "entry.json"), MAX_ENTRY))
        }
        if (canonical != null) QuantumAuthorizationBinding.capture(entry).requireCurrent(canonical)
        return entry
    }
    private fun ensureCapacity() {
        val records = summaries()
        if (records.size < MAX_HISTORY) return
        val referenced = workspace()?.operation
        val candidate = records.filter { (file, value) ->
            value.state == State.RELEASE_COMPLETED && value.operationId != referenced &&
                !io.exists(File(historyDirectory, file.name + ".tmp")) && run {
                    val active = File(root, value.birthHash + ".json")
                    !io.exists(active) || checkNotNull(outbox.readEntry(active)).id != value.operationId
                }
        }.minWithOrNull(compareBy<Pair<File, Summary>> { it.second.completedAt }.thenBy { it.second.operationId.toString() })
        checkNotNull(candidate) { "Completed history capacity has no safe eviction candidate" }
        io.sync(historyDirectory, "history_prune_reconfirm")
        io.unlink(candidate.first, "history_prune"); io.sync(historyDirectory, "history_prune")
    }
    private fun publish(value: Summary, label: String) {
        io.directory(historyDirectory); io.sync(root, "history_directory")
        val names = io.names(historyDirectory, 65)
        check(names.filter { it.endsWith(".tmp") }.all { it == "${value.operationId}.json.tmp" }) { "Unrelated summary write requires inspection" }
        io.atomic(File(historyDirectory, "${value.operationId}.json"), value.bytes(), MAX_SUMMARY, label)
    }
    internal fun finish(expiry: QuantumExpiredAuthorization, receipt: QuantumPaymentOutcomeReader.Receipt?, now: () -> Long): Summary {
        var work = workspace()
        val entry = snapshot(expiry.identity, expiry.operationId)
        expiry.requireCurrent(entry, now()); receipt?.requireCurrent(entry, now())
        if (work == null) {
            ensureCapacity()
            val stage = File(root, ".retiring-stage-${entry.id}")
            io.point("workspace.before_mkdir"); io.directory(stage); io.sync(root, "workspace_parent")
            work = Work(stage, entry.id, true)
            val original = checkNotNull(outbox.readStored(outbox.path(entry.identity), entry.identity))
            expiry.requireCurrent(original.entry, now())
            val bytes = original.bytes()
            io.create(File(stage, "entry.json"), bytes, MAX_ENTRY, "snapshot")
            val decision = decision(entry, bytes, expiry, receipt, now())
            io.create(File(stage, "decision.json"), decision.toByteArray(Charsets.UTF_8), MAX_DECISION, "decision")
            io.sync(stage, "workspace"); io.sync(root, "workspace_parent")
        }
        check(work.operation == entry.id)
        val decisionFile = File(work.directory, "decision.json")
        if (!io.exists(decisionFile)) {
            // No release was published; a complete retained entry can safely get a new conservative decision.
            check(summary(entry.id) == null)
            check(work.stage) { "Published retirement snapshot lacks its decision" }
            val original = checkNotNull(outbox.readStored(outbox.path(entry.identity), entry.identity))
            val canonical = original.entry
            check(canonical.id == work.operation) { "Staging operation differs from canonical" }
            expiry.requireCurrent(canonical, now())
            val entryFile = File(work.directory, "entry.json")
            if (io.exists(entryFile)) io.read(entryFile, MAX_ENTRY, allowEmpty = true)
            val retained = runCatching { outbox.readEntry(entryFile, entry.identity) }.getOrNull()
            if (retained != null) QuantumAuthorizationBinding.capture(entry).requireCurrent(retained)
            else {
                // No decision/summary has ever been published. Only retirement metadata is rebuilt.
                io.point("snapshot_rebuild.before_recheck")
                expiry.requireCurrent(checkNotNull(outbox.read(entry.identity)), now())
                io.unlink(entryFile, "snapshot_rebuild")
                io.create(entryFile, original.bytes(), MAX_ENTRY, "snapshot_rebuild")
                io.sync(work.directory, "snapshot_rebuild")
            }
            val raw = io.read(File(work.directory, "entry.json"), MAX_ENTRY)
            io.create(decisionFile, decision(entry, raw, expiry, receipt, now()).toByteArray(Charsets.UTF_8), MAX_DECISION, "decision")
            io.sync(work.directory, "workspace")
        }
        var retained = checkNotNull(outbox.readEntry(File(work.directory, "entry.json"), entry.identity))
        expiry.requireCurrent(retained, now())
        val pending = Summary(decode(io.read(decisionFile, MAX_DECISION)), State.RELEASE_PENDING, null)
        pending.requireEntry(retained, io.read(File(work.directory, "entry.json"), MAX_ENTRY))
        // Existing, parseable files may come from an interrupted write or a visible-but-unsynced
        // stage. Reconfirm both files and their containing directory on EVERY continuation path.
        io.syncFile(File(work.directory, "entry.json"), MAX_ENTRY, "snapshot_reconfirm")
        io.syncFile(decisionFile, MAX_DECISION, "decision_reconfirm")
        io.sync(work.directory, "workspace_reconfirm")
        if (work.stage) {
            val published = File(root, ".retiring-${work.operation}")
            io.point("workspace.before_rename"); Os.rename(work.directory.absolutePath, published.absolutePath)
            io.point("workspace.after_rename"); io.sync(root, "workspace_publish")
            work = Work(published, work.operation, false)
        }
        val previous = summary(entry.id)
        if (previous != null) check(previous.decision == pending.decision) { "Retirement decision conflict" }
        if (previous?.state == State.RELEASE_COMPLETED) {
            // Never delete a canonical based on completed JSON. Only cleanup the old private workspace.
            check(!io.exists(outbox.path(entry.identity))) { "Completed record conflicts with an active authorization" }
            cleanupCompleted(); return previous
        }
        publish(pending, "release_pending")
        io.point("canonical.before_recheck")
        retained = checkNotNull(outbox.readEntry(File(work.directory, "entry.json"), entry.identity))
        expiry.requireCurrent(retained, now())
        val current = outbox.read(entry.identity)
        if (current != null) {
            expiry.requireCurrent(current, now())
            io.unlink(outbox.path(entry.identity), "canonical")
        }
        // ENOENT is never success by itself: this sync is mandatory even when a prior attempt unlinked it.
        io.sync(root, "canonical_delete")
        val completed = Summary(pending.decision, State.RELEASE_COMPLETED, now())
        publish(completed, "release_completed")
        io.point("release_completed.durable")
        cleanupCompleted()
        return completed
    }
    /** May resume after entry/decision cleanup was interrupted. It never reads or unlinks a canonical. */
    internal fun cleanupCompleted() {
        val work = workspace() ?: return
        requireConsistentRecovery(work)
        val completed = checkNotNull(summary(work.operation)) { "No completed retirement record" }
        check(completed.state == State.RELEASE_COMPLETED)
        // A prior rename can be visible despite its directory sync having failed. Reconfirm durability
        // before destroying any retained snapshot, even on the metadata-only cleanup path.
        io.sync(historyDirectory, "completed_reconfirm")
        val decisionFile = File(work.directory, "decision.json")
        if (io.exists(decisionFile)) check(decode(io.read(decisionFile, MAX_DECISION)) == completed.decision)
        val entryFile = File(work.directory, "entry.json")
        if (io.exists(entryFile)) {
            val entry = checkNotNull(outbox.readEntry(entryFile))
            completed.requireEntry(entry, io.read(entryFile, MAX_ENTRY))
        }
        io.unlink(entryFile, "cleanup_entry"); io.sync(work.directory, "cleanup_entry")
        io.unlink(decisionFile, "cleanup_decision"); io.sync(work.directory, "cleanup_decision")
        io.point("cleanup.before_rmdir"); Os.remove(work.directory.absolutePath)
        io.point("cleanup.after_rmdir"); io.sync(root, "cleanup_workspace")
    }
    private fun decision(entry: QuantumPaymentOutbox.Entry, raw: ByteArray, expiry: QuantumExpiredAuthorization,
                         receipt: QuantumPaymentOutcomeReader.Receipt?, now: Long): String {
        expiry.requireCurrent(entry, now); receipt?.requireCurrent(entry, now)
        val original = entry.authorization()
        val result = when (receipt?.outcome) {
            QuantumPaymentOutcomeReader.Outcome.SenderSent -> Result.SENT
            QuantumPaymentOutcomeReader.Outcome.IncludedWithoutExactTransfer -> Result.INCLUDED_WITHOUT_EXACT_TRANSFER
            null -> Result.EXPIRED_UNVERIFIED
        }
        return JSONObject().put("version", 1).put("operation", entry.id.toString()).put("birth_hash", birth(entry.identity))
            .put("profile", entry.profile.json()).put("entry_sha256", digest(raw)).put("request_hash", QuantumPaymentQuote.hex(entry.requestHash))
            .put("role", original.role.name).put("epoch", original.epoch.toString()).put("nonce", original.nonce.toString())
            .put("authority_hash", QuantumPaymentQuote.hex(original.authorityHash)).put("public_key_sha256", digest(entry.signingPublicKey))
            .put("request_sha256", digest(entry.requestBOC)).put("signature_sha256", entry.signature?.let(::digest) ?: JSONObject.NULL)
            .put("message_sha256", entry.signedBOC?.let(::digest) ?: JSONObject.NULL).put("phase", entry.phase.name)
            .put("deploy", entry.deploy).put("intent", QuantumPaymentOutbox.encodeIntent(entry.intent)).put("deadline", entry.deadline)
            .put("proven_shard_time", expiry.provenShardTime).put("proof_target", expiry.provenTarget(entry, now))
            .put("result", result.name).put("transaction_hash", receipt?.transactionHash ?: JSONObject.NULL)
            .put("reason", if (receipt == null) "payment-outcome-unverified" else receipt.mismatchReason ?: JSONObject.NULL)
            .put("receipt_target", receipt?.provenTarget(entry, now) ?: JSONObject.NULL)
            .put("logical_time", receipt?.logicalTime ?: JSONObject.NULL).toString().also { check(it.toByteArray(Charsets.UTF_8).size <= MAX_DECISION) }
    }
    companion object {
        private const val MAX_HISTORY = 64
        private const val MAX_SUMMARY = 64 * 1024
        private const val MAX_DECISION = 31 * 1024
        private const val MAX_ENTRY = 2 * 1024 * 1024
        private fun birth(identity: TosQuantumSelfPayIdentity) = QuantumPaymentQuote.hex(identity.cell.hash().toByteArray())
        private fun digest(bytes: ByteArray) = QuantumPaymentQuote.hex(QuantumPaymentOutbox.digest(bytes))
        private fun uuid(value: String): UUID = UUID.fromString(value).also { check(it.toString() == value) }
        private fun hash(value: String) = value.also { check(it.matches(Regex("[0-9a-f]{64}"))) }
        private fun text(value: JSONObject, key: String) = QuantumPaymentQuote.string(value, key)
        private fun time(value: JSONObject, key: String): Long {
            val number = value.get(key); check(number is Int || number is Long)
            return (number as Number).toLong().also { check(it in 1..0xffffffffL) }
        }
        private fun decode(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        private fun json(bytes: ByteArray, maximum: Int): JSONObject {
            check(bytes.size in 1..maximum)
            val text = decode(bytes)
            var quoted = false; var escaped = false; var depth = 0
            for (c in text) {
                if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
                else when (c) { '"' -> quoted = true; '{', '[' -> { depth++; check(depth <= 16) }; '}', ']' -> { depth--; check(depth >= 0) } }
            }
            check(!quoted && depth == 0)
            return JSONObject(text)
        }
    }
}
