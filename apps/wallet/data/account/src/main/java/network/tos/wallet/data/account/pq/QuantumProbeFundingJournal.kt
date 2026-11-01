package network.tos.wallet.data.account.pq

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import network.tos.blockchain.ton.contract.TosQuantumSelfPayIdentity
import network.tos.blockchain.ton.contract.QuantumStoredSelfPayRequest
import org.ton.boc.BagOfCells
import java.io.*
import java.nio.channels.OverlappingFileLockException
import java.util.UUID
import java.math.BigInteger

/** Durable restricted funding journal. No network-dispatch or unlock API.
 * A reservation is never replaced or released, including after its deadline.
 */
internal class QuantumProbeFundingJournal(private val context: Context, private val fault: ((String) -> Unit)? = null) {
    enum class Phase { RESERVED, SIGNING, SIGNED, SUBMITTING, UNKNOWN }
    class Reservation private constructor(val operation: UUID, snapshot: ByteArray,
        val phase: Phase = Phase.RESERVED, signature: ByteArray = ByteArray(0),
        approval: ByteArray = ByteArray(0), originalEncoding: ByteArray? = null) {
        private val bytes = snapshot.copyOf()
        private val signatureBytes = signature.copyOf()
        private val approvalBytes = approval.copyOf()
        private val original = originalEncoding?.copyOf()
        internal val walletId: UUID get() = DataInputStream(ByteArrayInputStream(bytes)).use {
            UUID(it.readLong(), it.readLong())
        }
        internal val signatureDigest: String? get() = if (phase in setOf(Phase.SIGNED, Phase.SUBMITTING, Phase.UNKNOWN))
            java.security.MessageDigest.getInstance("SHA-256").digest(signatureBytes).joinToString("") { "%02x".format(it) }
            else null
        init {
            require(bytes.size in 1..MAX_FILE - 24)
            if (phase != Phase.RESERVED) require(bytes.size <= MAX_SNAPSHOT)
            require(if (phase in setOf(Phase.SIGNED, Phase.SUBMITTING, Phase.UNKNOWN)) signatureBytes.size == 7856 else signatureBytes.isEmpty())
            if (phase in setOf(Phase.SUBMITTING, Phase.UNKNOWN)) {
                require(QuantumProbeFundingApproval.decode(approvalBytes).operation == operation)
            } else require(approvalBytes.isEmpty())
        }
        internal fun beginSigning(): Reservation {
            check(phase == Phase.RESERVED)
            return Reservation(operation, bytes, Phase.SIGNING)
        }
        internal fun finishSigning(signature: ByteArray): Reservation {
            check(phase == Phase.SIGNING)
            return Reservation(operation, bytes, Phase.SIGNED, signature)
        }
        internal fun beginSubmission(approval: ByteArray): Reservation {
            check(phase == Phase.SIGNED) { "Funding dispatch already started or uncertain" }
            return Reservation(operation, bytes, Phase.SUBMITTING, signatureBytes, approval)
        }
        internal fun unknown(): Reservation {
            check(phase == Phase.SUBMITTING || phase == Phase.UNKNOWN)
            return Reservation(operation, bytes, Phase.UNKNOWN, signatureBytes, approvalBytes)
        }
        internal fun signedForEstimate(prior: QuantumRecoveryControlJournal.Entry, probe: QuantumRecoveryProbeJournal.Entry,
                                       fresh: QuantumAccountReader.Observation, now: Long): Pair<QuantumPreparedProbeFunding, ByteArray> {
            check(phase == Phase.SIGNED) { "Funding signature is not durably available" }
            val plan = restore(prior, probe, fresh, now)
            return plan to plan.signedMessage(signatureBytes, now)
        }
        fun requirePlan(plan: QuantumPreparedProbeFunding, prior: QuantumRecoveryControlJournal.Entry,
                        probe: QuantumRecoveryProbeJournal.Entry, now: Long) {
            check(bytes.contentEquals(snapshot(plan, prior, probe, now))) { "Probe funding reservation changed" }
        }
        /** Reconstruct the saved request after fresh authentication; never renew its deadline. */
        internal fun restore(prior: QuantumRecoveryControlJournal.Entry, probe: QuantumRecoveryProbeJournal.Entry,
                             fresh: QuantumAccountReader.Observation, now: Long): QuantumPreparedProbeFunding {
            check(phase == Phase.RESERVED || phase == Phase.SIGNED) { "Prior funding signing or submission is uncertain; retry forbidden" }
            return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val walletId = UUID(input.readLong(), input.readLong())
                val globalId = input.readInt(); val root = input.readUTF(); val file = input.readUTF()
                require(root.matches(Regex("[0-9a-f]{64}")) && file.matches(Regex("[0-9a-f]{64}")))
                val profile = QuantumNetworkProfile(globalId, root, file)
                val amountText = input.readUTF(); require(amountText.matches(Regex("[1-9][0-9]{0,8}")))
                val amount = BigInteger(amountText); QuantumPreparedProbeFunding.requireAmount(amount)
                fun field(): ByteArray {
                    val size = input.readInt(); require(size in 1..131072 && size <= input.available())
                    return ByteArray(size).also(input::readFully)
                }
                val identity = TosQuantumSelfPayIdentity.fromBoc(field())
                check(field().contentEquals(prior.encode())) { "Prior control record changed after reservation" }
                check(field().contentEquals(probe.encode())) { "Possession probe changed after reservation" }
                val stored = QuantumStoredSelfPayRequest.parse(field()); require(input.read() == -1)
                require(walletId == fresh.id && identity == fresh.identity && sameProfile(profile, fresh.profile))
                val handoff = QuantumRecoveryControlHandoff.acquire(prior, fresh, now)
                QuantumPreparedProbeFunding(probe, fresh, amount, now, 120, handoff, stored.deadline).also { plan ->
                    plan.requireBound(stored.cell)
                    requirePlan(plan, prior, probe, now)
                    if (phase == Phase.SIGNED) plan.requireSignature(signatureBytes)
                }
            }
        }
        internal fun encode(): ByteArray {
            original?.let { return it.copyOf() }
            return ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                val submitting = phase == Phase.SUBMITTING || phase == Phase.UNKNOWN
                out.writeInt(if (submitting) 0x51504633 else 0x51504632)
                out.writeLong(operation.mostSignificantBits); out.writeLong(operation.leastSignificantBits)
                out.writeByte(phase.ordinal)
                out.writeInt(bytes.size); out.write(bytes)
                out.writeInt(signatureBytes.size); out.write(signatureBytes)
                if (submitting) { out.writeInt(approvalBytes.size); out.write(approvalBytes) }
            }
        }.toByteArray().also { require(it.size <= MAX_FILE) }
        }
        companion object {
            internal fun create(plan: QuantumPreparedProbeFunding, prior: QuantumRecoveryControlJournal.Entry,
                                probe: QuantumRecoveryProbeJournal.Entry, now: Long) =
                Reservation(UUID.randomUUID(), snapshot(plan, prior, probe, now))
            internal fun decode(bytes: ByteArray): Reservation = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(bytes.size in 25..MAX_FILE)
                val magic = input.readInt(); require(magic in setOf(0x51504631, 0x51504632, 0x51504633))
                val operation = UUID(input.readLong(), input.readLong())
                val phase = if (magic == 0x51504631) Phase.RESERVED else
                    Phase.entries.getOrNull(input.readUnsignedByte()) ?: error("Funding phase")
                require((magic == 0x51504633) == (phase == Phase.SUBMITTING || phase == Phase.UNKNOWN))
                val size = input.readInt(); require(size > 0 && size <= input.available())
                val snapshot = ByteArray(size).also(input::readFully)
                val signature = if (magic == 0x51504631) ByteArray(0) else {
                    val count = input.readInt(); require(count in 0..7856 && count <= input.available())
                    ByteArray(count).also(input::readFully)
                }
                val approval = if (magic == 0x51504633) {
                    val count = input.readInt(); require(count == QuantumProbeFundingApproval.SIZE && count == input.available())
                    ByteArray(count).also(input::readFully)
                } else ByteArray(0)
                require(input.read() == -1)
                Reservation(operation, snapshot, phase, signature, approval, bytes)
            }
            private fun snapshot(plan: QuantumPreparedProbeFunding, prior: QuantumRecoveryControlJournal.Entry,
                                 probe: QuantumRecoveryProbeJournal.Entry, now: Long): ByteArray {
                plan.requireHandoff(prior, now); plan.requireProbe(probe)
                return ByteArrayOutputStream().also { buffer ->
                    DataOutputStream(buffer).use { out ->
                        out.writeLong(plan.walletId.mostSignificantBits); out.writeLong(plan.walletId.leastSignificantBits)
                        out.writeInt(plan.profile.globalId); out.writeUTF(plan.profile.rootHash); out.writeUTF(plan.profile.fileHash)
                        out.writeUTF(plan.amount.toString())
                        for (bytes in listOf(plan.identity.toBoc(), prior.encode(), probe.encode(), BagOfCells(plan.request.request).toByteArray())) {
                            require(bytes.size <= 131072); out.writeInt(bytes.size); out.write(bytes)
                        }
                    }
                }.toByteArray().also { require(it.size <= MAX_SNAPSHOT) }
            }
        }
    }
    private val store = QuantumProbeFundingStore(context.noBackupFilesDir, fault)
    private fun key(identity: TosQuantumSelfPayIdentity) = identity.cell.hash().toByteArray().joinToString("") { "%02x".format(it) }
    fun pending(identity: TosQuantumSelfPayIdentity): Reservation? = store.pending(key(identity))?.let(Reservation::decode)
    fun reserve(plan: QuantumPreparedProbeFunding, now: Long): Reservation =
        QuantumPaymentOutbox(context).withRecoveryControlIsolation(plan.identity) {
            // Read originals under the shared recovery custody lease; do not trust caller copies.
            val prior = checkNotNull(QuantumRecoveryControlJournal(context).pending(plan.identity))
            val probe = checkNotNull(QuantumRecoveryProbeJournal(context).pending(plan.identity))
            val entry = Reservation.create(plan, prior, probe, now)
            store.reserve(key(plan.identity), entry.encode())
            entry
        }
    fun resume(identity: TosQuantumSelfPayIdentity, operation: UUID, fresh: QuantumAccountReader.Observation,
               now: Long): QuantumPreparedProbeFunding = QuantumPaymentOutbox(context).withRecoveryControlIsolation(identity) {
        val reservation = checkNotNull(pending(identity)); check(reservation.operation == operation)
        require(fresh.identity == identity)
        reservation.restore(checkNotNull(QuantumRecoveryControlJournal(context).pending(identity)),
            checkNotNull(QuantumRecoveryProbeJournal(context).pending(identity)), fresh, now)
    }
    /** Read-only exact signed message, reauthenticated under the recovery lease; no signer or write. */
    fun signedForEstimate(identity: TosQuantumSelfPayIdentity, operation: UUID, fresh: QuantumAccountReader.Observation,
                          now: Long): Pair<QuantumPreparedProbeFunding, ByteArray> =
        QuantumPaymentOutbox(context).withRecoveryControlIsolation(identity) {
            require(fresh.identity == identity)
            val saved = checkNotNull(pending(identity))
            check(saved.operation == operation)
            saved.signedForEstimate(checkNotNull(QuantumRecoveryControlJournal(context).pending(identity)),
                checkNotNull(QuantumRecoveryProbeJournal(context).pending(identity)), fresh, now)
        }
    /** A fresh native quote is consumed before persisting the single initial dispatch transition. */
    fun beginSubmission(quote: QuantumProbeFundingQuote, fresh: QuantumAccountReader.Observation, now: Long):
        Pair<QuantumPreparedProbeFunding, ByteArray> = QuantumPaymentOutbox(context).withRecoveryControlIsolation(quote.plan.identity) {
        val identity = quote.plan.identity
        require(fresh.identity == identity)
        val saved = checkNotNull(pending(identity)); check(saved.operation == quote.operation)
        val (plan, message) = saved.signedForEstimate(checkNotNull(QuantumRecoveryControlJournal(context).pending(identity)),
            checkNotNull(QuantumRecoveryProbeJournal(context).pending(identity)), fresh, now)
        quote.requireFresh(saved.operation, plan, message, now)
        val approval = quote.approvalRecord()
        QuantumProbeFundingApproval.decode(approval).requireBound(saved.operation, plan, message)
        val submitting = saved.beginSubmission(approval)
        store.transition(key(identity), saved.encode(), submitting.encode())
        plan to message
    }
    /** A transport response cannot prove inclusion. Uncertainty never returns to a dispatchable state. */
    fun unknown(identity: TosQuantumSelfPayIdentity, operation: UUID) =
        QuantumPaymentOutbox(context).withRecoveryControlIsolation(identity) {
            val saved = checkNotNull(pending(identity)); check(saved.operation == operation)
            val uncertain = saved.unknown()
            if (saved.phase != Phase.UNKNOWN) store.transition(key(identity), saved.encode(), uncertain.encode())
        }
    /** Callback follows authenticated restore and durable SIGNING; failure never returns to RESERVED. */
    fun sign(identity: TosQuantumSelfPayIdentity, operation: UUID, fresh: QuantumAccountReader.Observation,
             now: Long, reviewedDigest: ByteArray, signer: (QuantumPreparedProbeFunding) -> ByteArray) =
        QuantumPaymentOutbox(context).withRecoveryControlIsolation(identity) {
            val reserved = checkNotNull(pending(identity))
            check(reserved.operation == operation && reserved.phase == Phase.RESERVED)
            require(fresh.identity == identity)
            val plan = reserved.restore(checkNotNull(QuantumRecoveryControlJournal(context).pending(identity)),
                checkNotNull(QuantumRecoveryProbeJournal(context).pending(identity)), fresh, now)
            plan.requireReviewedRequest(reviewedDigest)
            val signing = reserved.beginSigning()
            store.transition(key(identity), reserved.encode(), signing.encode())
            plan.requireFresh(System.currentTimeMillis() / 1000)
            val signature = signer(plan)
            try {
                plan.requireSignature(signature)
                store.transition(key(identity), signing.encode(), signing.finishSigning(signature).encode())
            } finally { signature.fill(0) }
        }
    companion object {
        private const val MAX_FILE = 262144
        private const val MAX_SNAPSHOT = MAX_FILE - 29 - 7856
    }
}

/** Storage only: bytes read here establish no proof, signature or release authority. */
internal class QuantumProbeFundingStore(private val parent: File, private val fault: ((String) -> Unit)? = null) {
    private val directory = File(parent, "quantum-probe-funding")
    private fun file(key: String): File {
        require(key.matches(Regex("[0-9a-f]{64}")))
        return File(directory, "$key.qpf")
    }
    private fun sync(file: File) {
        val fd = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    }
    private fun validate(fd: FileDescriptor) {
        val st = Os.fstat(fd)
        check(st.st_mode and OsConstants.S_IFMT == OsConstants.S_IFREG && st.st_uid == Os.getuid() &&
            st.st_mode and 0x3f == 0 && st.st_nlink == 1L)
    }
    private fun <T> locked(body: () -> T): T = synchronized(mutex) {
        try { Os.mkdir(directory.path, 0x1c0) } catch (e: ErrnoException) { if (e.errno != OsConstants.EEXIST) throw e }
        val st = Os.lstat(directory.path)
        check(st.st_mode and OsConstants.S_IFMT == OsConstants.S_IFDIR && st.st_uid == Os.getuid() && st.st_mode and 0x3f == 0)
        sync(directory); sync(parent)
        val fd = Os.open(File(directory, ".lock").path, OsConstants.O_RDWR or OsConstants.O_CREAT or OsConstants.O_NOFOLLOW, 0x180)
        withOwnedOutputStream(fd) { stream ->
            validate(fd)
            val lease = try { stream.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            checkNotNull(lease).use {
                val names = checkNotNull(directory.list())
                check(names.size <= 129 && names.all { it == ".lock" || it.matches(Regex("[0-9a-f]{64}\\.qpf(?:\\.next)?")) })
                check(names.none { it.endsWith(".next") }) { "Probe funding reservation write uncertain" }
                body()
            }
        }
    }
    private fun read(key: String): ByteArray? {
        val fd = try { Os.open(file(key).path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0) }
            catch (e: ErrnoException) { if (e.errno == OsConstants.ENOENT) return null else throw e }
        return withOwnedInputStream(fd) { input ->
            validate(fd); val size = Os.fstat(fd).st_size; check(size in 1..MAX_FILE.toLong())
            val bytes = ByteArray(size.toInt()); DataInputStream(input).readFully(bytes); check(input.read() == -1)
            bytes
        }
    }
    fun pending(key: String): ByteArray? = locked { read(key) }
    private fun write(key: String, bytes: ByteArray) {
        val target = file(key); val next = File(target.path + ".next")
        val fd = Os.open(next.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW, 0x180)
        withOwnedOutputStream(fd) { out -> validate(fd); out.write(bytes); fault?.invoke("before_file_sync"); out.fd.sync() }
        fault?.invoke("before_rename"); Os.rename(next.path, target.path)
        fault?.invoke("before_directory_sync"); sync(directory)
    }
    fun reserve(key: String, bytes: ByteArray) {
        require(bytes.size in 1..MAX_FILE)
        val saved = bytes.copyOf()
        locked {
            check(read(key) == null) { "Probe funding already reserved" }
            check(checkNotNull(directory.list()).count { it.endsWith(".qpf") } < 64)
            write(key, saved)
        }
    }
    /** Storage CAS only. The journal must validate the permitted phase transition first. */
    fun transition(key: String, expected: ByteArray, replacement: ByteArray) {
        require(expected.size in 1..MAX_FILE && replacement.size in 1..MAX_FILE)
        val before = expected.copyOf(); val after = replacement.copyOf()
        require(!before.contentEquals(after)) { "Unchanged funding transition" }
        locked {
            check(checkNotNull(read(key)) { "Missing funding reservation" }.contentEquals(before)) {
                "Funding reservation changed concurrently"
            }
            write(key, after)
        }
    }
    companion object { private val mutex = Any(); private const val MAX_FILE = 262144 }
}
