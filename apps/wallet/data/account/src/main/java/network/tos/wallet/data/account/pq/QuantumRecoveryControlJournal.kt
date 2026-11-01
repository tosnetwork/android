package network.tos.wallet.data.account.pq

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import network.tos.blockchain.ton.contract.*
import network.tos.security.pq.QuantumCrypto
import network.tos.security.pq.QuantumPurpose
import network.tos.security.pq.QuantumRole
import org.ton.boc.BagOfCells
import org.ton.cell.buildCell
import java.io.*
import java.nio.channels.OverlappingFileLockException
import java.util.UUID

/** One durable control slot per birth identity. No reset/delete, broadcast or unlock API.
 * A torn .next or uncertain SIGNING permanently blocks replacement until a separately
 * authenticated recovery procedure is implemented. No secrets are stored here. */
internal class QuantumRecoveryControlJournal(
    private val context: Context,
    private val fault: ((String) -> Unit)? = null,
) {
    enum class Phase { RESERVED, SIGNING, SIGNED, SUBMITTING, UNKNOWN }
    class Entry internal constructor(val operation: UUID, val walletId: UUID,
        val identity: TosQuantumSelfPayIdentity, val profile: QuantumNetworkProfile,
        stateBOC: ByteArray, request: ByteArray, val phase: Phase, signature: ByteArray, approval: ByteArray = ByteArray(0),
    ) {
        private val stateBytes = stateBOC.copyOf()
        private val requestBytes = request.copyOf()
        private val signatureBytes = signature.copyOf()
        private val approvalBytes = approval.copyOf()
        val requestHash: ByteArray get() = authorization.cell.hash().toByteArray()
        val deadline: Long get() = authorization.deadline
        val kind: QuantumPreparedRecoveryControl.Kind get() = if (authorization.kind == 3)
            QuantumPreparedRecoveryControl.Kind.LOCK_PRIMARY else QuantumPreparedRecoveryControl.Kind.REQUIRE_RESCUE
        internal fun signedMessage(): ByteArray {
            check(phase in setOf(Phase.SIGNED, Phase.SUBMITTING, Phase.UNKNOWN))
            val body = buildCell { storeRef(authorization.cell); storeRef(TosPqWallet.byteChain(signatureBytes)) }
            return BagOfCells(identity.wallet.external(body, false)).toByteArray()
        }
        internal fun requireCurrent(observation: QuantumAccountReader.Observation, now: Long) {
            require(observation.id == walletId && observation.identity == identity && sameProfile(observation.profile, profile))
            observation.requireFresh(now)
            QuantumSelfPayNetwork.requireRecoveryBase(observation.network, profile.globalId, now)
            observation.network.requireSameCheckpoint(observation.proof)
            val current = (QuantumSelfPayAccount.bind(identity, observation.proof, now, 300).snapshot(now).status
                as? QuantumSelfPayAccount.Status.Active)?.state ?: error("Recovery control account no longer active")
            check(current.cell.hash() == state.cell.hash() && now in 1 until deadline) { "Recovery control state or deadline changed" }
        }
        private val authorization get() = QuantumStoredSelfPayRequest.parse(requestBytes)
        private val state get() = QuantumSelfPayState.parse(BagOfCells(stateBytes).roots.single())
        init {
            TosQuantumSelfPayIdentity.preflightOrdinaryBoc(stateBytes, 32768)
            val decoded = authorization
            decoded.requireIdentity(identity)
            require(profile.globalId == decoded.globalId && state.walletId == identity.wallet.walletId)
            require(decoded.role == QuantumAuthRole.RESCUE && decoded.kind in setOf(1, 3))
            require(decoded.authorityHash.contentEquals(state.authority.hash) && decoded.epoch == state.epoch && decoded.nonce == state.rescueNonce)
            val rebuilt = TosQuantumSelfPayRequest(identity.address, state, QuantumAuthRole.RESCUE, decoded.deadline,
                if (decoded.kind == 1) QuantumSelfPayAction.ConfigureRequired else QuantumSelfPayAction.LockPrimary,
                decoded.deadline - 1)
            require(rebuilt.request.hash() == decoded.cell.hash())
            if (phase in setOf(Phase.SIGNED, Phase.SUBMITTING, Phase.UNKNOWN)) {
                check(QuantumCrypto.verify(QuantumRole.RESCUE, QuantumPurpose.SELF_PAY,
                    state.authority.rescueKey, requestHash, signatureBytes)) { "Stored recovery signature mismatch" }
            } else require(signatureBytes.isEmpty())
            if (phase in setOf(Phase.SUBMITTING, Phase.UNKNOWN)) QuantumRecoveryControlApproval.decode(approvalBytes).requireBound(this)
            else require(approvalBytes.isEmpty())
        }
        internal fun requirePlan(plan: QuantumPreparedRecoveryControl) {
            require(walletId == plan.walletId && identity == plan.identity && sameProfile(profile, plan.profile))
            require(requestHash.contentEquals(plan.request.digest) && stateBytes.contentEquals(plan.boundStateBOC))
        }
        internal fun next(phase: Phase, signature: ByteArray = signatureBytes, approval: ByteArray = approvalBytes) =
            Entry(operation, walletId, identity, profile, stateBytes, requestBytes, phase, signature, approval)
        internal fun encode(): ByteArray = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(0x51524331); out.writeByte(phase.ordinal)
                for (id in listOf(operation, walletId)) { out.writeLong(id.mostSignificantBits); out.writeLong(id.leastSignificantBits) }
                out.writeInt(profile.globalId)
                for (hex in listOf(profile.rootHash, profile.fileHash)) out.write(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
                for (bytes in listOf(identity.toBoc(), stateBytes, requestBytes, signatureBytes)) {
                    require(bytes.size <= 32768); out.writeInt(bytes.size); out.write(bytes)
                }
                if (phase in setOf(Phase.SUBMITTING, Phase.UNKNOWN)) { out.writeInt(approvalBytes.size); out.write(approvalBytes) }
            }
        }.toByteArray().also { require(it.size <= MAX_FILE) }
        companion object {
            internal fun decode(bytes: ByteArray): Entry = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readInt() == 0x51524331)
                val phase = Phase.entries.getOrNull(input.readUnsignedByte()) ?: error("Recovery journal phase")
                fun id() = UUID(input.readLong(), input.readLong())
                val operation = id(); val walletId = id(); val globalId = input.readInt()
                fun fixed(size: Int) = ByteArray(size).also(input::readFully)
                fun hex() = fixed(32).joinToString("") { "%02x".format(it) }
                val profile = QuantumNetworkProfile(globalId, hex(), hex())
                fun field(): ByteArray { val size = input.readInt(); require(size in 0..32768 && size <= input.available()); return fixed(size) }
                val identity = TosQuantumSelfPayIdentity.fromBoc(field()); val state = field(); val request = field(); val signature = field()
                val approval = if (phase in setOf(Phase.SUBMITTING, Phase.UNKNOWN)) field() else ByteArray(0)
                val entry = Entry(operation, walletId, identity, profile, state, request, phase, signature, approval)
                require(input.read() == -1) { "Recovery journal tail" }; entry
            }
        }
    }

    private val directory = File(context.noBackupFilesDir, "quantum-recovery-controls")
    private val outbox by lazy { QuantumPaymentOutbox(context) }
    private fun sync(file: File) {
        val fd = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    }
    private fun name(identity: TosQuantumSelfPayIdentity) = identity.cell.hash().toByteArray().joinToString("") { "%02x".format(it) }
    private fun file(identity: TosQuantumSelfPayIdentity) = File(directory, name(identity) + ".qrc")
    private fun validate(fd: java.io.FileDescriptor) {
        val stat = Os.fstat(fd)
        check(stat.st_mode and OsConstants.S_IFMT == OsConstants.S_IFREG && stat.st_uid == Os.getuid() &&
            stat.st_mode and 0x3f == 0 && stat.st_nlink == 1L)
    }
    private fun <T> locked(body: () -> T): T = synchronized(mutex) {
        try { Os.mkdir(directory.path, 0x1c0) }
        catch (e: ErrnoException) { if (e.errno != OsConstants.EEXIST) throw e }
        val stat = Os.lstat(directory.path)
        check(stat.st_mode and OsConstants.S_IFMT == OsConstants.S_IFDIR && stat.st_uid == Os.getuid() && stat.st_mode and 0x3f == 0)
        sync(directory); sync(context.noBackupFilesDir)
        val fd = Os.open(File(directory, ".lock").path, OsConstants.O_RDWR or OsConstants.O_CREAT or OsConstants.O_NOFOLLOW, 0x180)
        FileOutputStream(fd).use { stream ->
            validate(fd)
            val lease = try { stream.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            checkNotNull(lease) { "Recovery journal busy" }.use {
                val names = checkNotNull(directory.list())
                check(names.size <= 129 && names.all { it == ".lock" || it.matches(Regex("[0-9a-f]{64}\\.qrc(?:\\.next)?")) })
                check(names.none { it.endsWith(".next") }) { "Recovery journal write uncertain" }
                body()
            }
        }
    }
    private fun read(identity: TosQuantumSelfPayIdentity): Entry? {
        val fd = try { Os.open(file(identity).path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0) }
        catch (e: ErrnoException) { if (e.errno == OsConstants.ENOENT) return null else throw e }
        return FileInputStream(fd).use { input ->
            validate(fd); val size = Os.fstat(fd).st_size; check(size in 1..MAX_FILE.toLong())
            val bytes = ByteArray(size.toInt()); DataInputStream(input).readFully(bytes); check(input.read() == -1)
            Entry.decode(bytes).also { require(it.identity == identity) }
        }
    }
    private fun write(entry: Entry) {
        val target = file(entry.identity); val stage = File(target.path + ".next")
        val fd = Os.open(stage.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW, 0x180)
        FileOutputStream(fd).use { output -> validate(fd); output.write(entry.encode()); fault?.invoke("before_file_sync"); output.fd.sync() }
        fault?.invoke("before_rename"); Os.rename(stage.path, target.path)
        fault?.invoke("before_directory_sync"); sync(directory)
    }
    fun pending(identity: TosQuantumSelfPayIdentity): Entry? = locked { read(identity) }
    fun reserve(plan: QuantumPreparedRecoveryControl, now: Long): Entry = outbox.withRecoveryControlIsolation(plan.identity) {
        locked {
            plan.requireFresh(now)
            check(read(plan.identity) == null) { "Recovery control already pending" }
            check(checkNotNull(directory.list()).count { it.endsWith(".qrc") } < 64)
            Entry(UUID.randomUUID(), plan.walletId, plan.identity, plan.profile, plan.boundStateBOC,
                BagOfCells(plan.request.request).toByteArray(), Phase.RESERVED, ByteArray(0)).also(::write)
        }
    }
    fun sign(plan: QuantumPreparedRecoveryControl, fresh: QuantumAccountReader.Observation, now: Long,
             operation: UUID, signer: () -> ByteArray) = outbox.withRecoveryControlIsolation(plan.identity) {
        locked {
            plan.requireRevalidated(fresh, now)
            val entry = checkNotNull(read(plan.identity)); entry.requirePlan(plan)
            check(entry.operation == operation && entry.phase == Phase.RESERVED) { "Recovery signing cannot be repeated" }
            val signing = entry.next(Phase.SIGNING); write(signing)
            // Even a throwing callback may already have signed; retain SIGNING forever until proven recovery.
            val signature = signer()
            try { write(signing.next(Phase.SIGNED, signature)) } finally { signature.fill(0) }
        }
    }
    /** One initial dispatch only. A stored approval or UNKNOWN can never mint a retry. */
    fun beginSubmission(quote: QuantumRecoveryControlQuote, now: Long): ByteArray = outbox.withRecoveryControlIsolation(quote.entry.identity) {
        locked {
            val current = checkNotNull(read(quote.entry.identity))
            check(current.phase == Phase.SIGNED) { "Recovery submission already started or uncertain" }
            quote.requireFresh(current, now)
            val submitting = current.next(Phase.SUBMITTING, approval = quote.approvalRecord())
            write(submitting)
            submitting.signedMessage()
        }
    }
    fun unknown(identity: TosQuantumSelfPayIdentity, operation: UUID) = locked {
        val current = checkNotNull(read(identity))
        check(current.operation == operation && current.phase in setOf(Phase.SUBMITTING, Phase.UNKNOWN))
        write(current.next(Phase.UNKNOWN))
    }
    companion object { private val mutex = Any(); private const val MAX_FILE = 131072 }
}
