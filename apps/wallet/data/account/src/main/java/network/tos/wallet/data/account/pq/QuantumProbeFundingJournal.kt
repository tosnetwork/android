package network.tos.wallet.data.account.pq

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import network.tos.blockchain.ton.contract.TosQuantumSelfPayIdentity
import org.ton.boc.BagOfCells
import java.io.*
import java.nio.channels.OverlappingFileLockException
import java.util.UUID

/** Durable reservation only. Neither a signing capability nor a broadcast/unlock API.
 * A reservation is never replaced or released, including after its deadline.
 */
internal class QuantumProbeFundingJournal(private val context: Context, private val fault: ((String) -> Unit)? = null) {
    class Reservation private constructor(val operation: UUID, snapshot: ByteArray) {
        private val bytes = snapshot.copyOf()
        fun requirePlan(plan: QuantumPreparedProbeFunding, prior: QuantumRecoveryControlJournal.Entry,
                        probe: QuantumRecoveryProbeJournal.Entry, now: Long) {
            check(bytes.contentEquals(snapshot(plan, prior, probe, now))) { "Probe funding reservation changed" }
        }
        internal fun encode(): ByteArray = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(0x51504631)
                out.writeLong(operation.mostSignificantBits); out.writeLong(operation.leastSignificantBits)
                out.writeInt(bytes.size); out.write(bytes)
            }
        }.toByteArray().also { require(it.size <= MAX_FILE) }
        companion object {
            internal fun create(plan: QuantumPreparedProbeFunding, prior: QuantumRecoveryControlJournal.Entry,
                                probe: QuantumRecoveryProbeJournal.Entry, now: Long) =
                Reservation(UUID.randomUUID(), snapshot(plan, prior, probe, now))
            internal fun decode(bytes: ByteArray): Reservation = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(bytes.size in 25..MAX_FILE && input.readInt() == 0x51504631)
                val operation = UUID(input.readLong(), input.readLong())
                val size = input.readInt(); require(size > 0 && size == input.available())
                Reservation(operation, ByteArray(size).also(input::readFully))
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
                }.toByteArray().also { require(it.size <= MAX_FILE - 24) }
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
    companion object { private const val MAX_FILE = 262144 }
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
        FileOutputStream(fd).use { stream ->
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
        return FileInputStream(fd).use { input ->
            validate(fd); val size = Os.fstat(fd).st_size; check(size in 1..MAX_FILE.toLong())
            val bytes = ByteArray(size.toInt()); DataInputStream(input).readFully(bytes); check(input.read() == -1)
            bytes
        }
    }
    fun pending(key: String): ByteArray? = locked { read(key) }
    fun reserve(key: String, bytes: ByteArray) {
        require(bytes.size in 1..MAX_FILE)
        locked {
                check(read(key) == null) { "Probe funding already reserved" }
                check(checkNotNull(directory.list()).count { it.endsWith(".qpf") } < 64)
                val target = file(key); val next = File(target.path + ".next")
                val fd = Os.open(next.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW, 0x180)
                FileOutputStream(fd).use { out -> validate(fd); out.write(bytes); fault?.invoke("before_file_sync"); out.fd.sync() }
                fault?.invoke("before_rename"); Os.rename(next.path, target.path)
                fault?.invoke("before_directory_sync"); sync(directory)
        }
    }
    companion object { private val mutex = Any(); private const val MAX_FILE = 262144 }
}
