package network.tos.wallet.data.account.pq

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import network.tos.blockchain.ton.contract.*
import network.tos.security.pq.*
import org.ton.boc.BagOfCells
import java.io.*
import java.nio.channels.OverlappingFileLockException
import java.util.UUID

/** New-key possession only. No transfer, old-key signing, reset or recovery-unlock API. */
internal class QuantumRecoveryProbeJournal(private val context: Context, private val fault: ((String) -> Unit)? = null) {
    enum class Phase { RESERVED, SIGNING, SIGNED }
    class Entry internal constructor(val operation: UUID, val walletId: UUID, val identity: TosQuantumSelfPayIdentity,
        val profile: QuantumNetworkProfile, state: ByteArray, val successor: QuantumSelfPayAuthority,
        challenge: ByteArray, val deadline: Long, materialDigest: ByteArray, val phase: Phase,
        primarySignature: ByteArray = ByteArray(0), rescueSignature: ByteArray = ByteArray(0),
    ) {
        private val stateBytes = state.copyOf()
        private val challengeBytes = challenge.copyOf()
        private val materialBytes = materialDigest.copyOf()
        private val primary = primarySignature.copyOf()
        private val rescue = rescueSignature.copyOf()
        val materialDigest get() = materialBytes.copyOf()
        val challenge get() = challengeBytes.copyOf()
        val probe: TosQuantumSelfPayProbe
        init {
            require(deadline in 1..0xffffffffL && materialBytes.size == 32 && materialBytes.any { it != 0.toByte() })
            TosQuantumSelfPayIdentity.preflightOrdinaryBoc(stateBytes, 32768)
            val current = QuantumSelfPayState.parse(BagOfCells(stateBytes).roots.single())
            require(current.walletId == identity.wallet.walletId && successor.globalId == profile.globalId &&
                current.authority.globalId == profile.globalId && successor.namespace.contentEquals(current.authority.namespace))
            require(!successor.primaryKey.contentEquals(current.authority.primaryKey) && !successor.rescueKey.contentEquals(current.authority.rescueKey))
            require(current.retired == 0 || successor.policy == QuantumPolicy.REQUIRED)
            probe = TosQuantumSelfPayProbe(identity.address, successor, challengeBytes, deadline, deadline - 1)
            if (phase == Phase.SIGNED) {
                check(QuantumCrypto.verify(QuantumRole.PRIMARY, QuantumPurpose.SELF_PAY_POP, successor.primaryKey, probe.digest, primary))
                check(QuantumCrypto.verify(QuantumRole.RESCUE, QuantumPurpose.SELF_PAY_POP, successor.rescueKey, probe.digest, rescue))
            } else require(primary.isEmpty() && rescue.isEmpty())
        }
        fun signedBody(): ByteArray {
            check(phase == Phase.SIGNED)
            return BagOfCells(probe.submission(primary, rescue)).toByteArray()
        }
        internal fun signaturePair(): Pair<ByteArray, ByteArray> {
            check(phase == Phase.SIGNED)
            return Pair(primary.copyOf(), rescue.copyOf())
        }
        internal fun requireCurrent(fresh: QuantumAccountReader.Observation, now: Long) {
            require(fresh.id == walletId && fresh.identity == identity && sameProfile(fresh.profile, profile))
            fresh.requireFresh(now)
            QuantumSelfPayNetwork.requireRecoveryBase(fresh.network, profile.globalId, now)
            fresh.network.requireSameCheckpoint(fresh.proof)
            check(maxOf(now, fresh.network.masterchainTime, fresh.proof.provenAccountTime(QuantumAccountReader.rawAddress(identity))) < deadline)
            val state = (QuantumSelfPayAccount.bind(identity, fresh.proof, now, 300).snapshot(now).status
                as? QuantumSelfPayAccount.Status.Active)?.state ?: error("Probe wallet no longer active")
            check(state.cell.hash() == BagOfCells(stateBytes).roots.single().hash())
        }
        internal fun next(phase: Phase, signatures: Pair<ByteArray, ByteArray> = Pair(primary, rescue)) =
            Entry(operation, walletId, identity, profile, stateBytes, successor, challengeBytes, deadline, materialBytes, phase,
                signatures.first, signatures.second)
        internal fun encode(): ByteArray = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(0x51504a31); out.writeByte(phase.ordinal)
                for (id in listOf(operation, walletId)) { out.writeLong(id.mostSignificantBits); out.writeLong(id.leastSignificantBits) }
                out.writeInt(profile.globalId)
                for (hex in listOf(profile.rootHash, profile.fileHash)) out.write(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
                out.writeLong(deadline); out.write(challengeBytes); out.write(materialBytes)
                for (bytes in listOf(identity.toBoc(), stateBytes, BagOfCells(successor.cell).toByteArray(), primary, rescue)) {
                    require(bytes.size <= 32768); out.writeInt(bytes.size); out.write(bytes)
                }
            }
        }.toByteArray().also { require(it.size <= MAX_FILE) }
        companion object {
            internal fun decode(bytes: ByteArray): Entry = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(bytes.size in 1..MAX_FILE && input.readInt() == 0x51504a31)
                val phase = Phase.entries.getOrNull(input.readUnsignedByte()) ?: error("Probe phase")
                fun id() = UUID(input.readLong(), input.readLong())
                val operation = id(); val wallet = id(); val global = input.readInt()
                fun fixed(size: Int) = ByteArray(size).also(input::readFully)
                fun hex() = fixed(32).joinToString("") { "%02x".format(it) }
                val profile = QuantumNetworkProfile(global, hex(), hex()); val deadline = input.readLong()
                val challenge = fixed(32); val material = fixed(32)
                fun field(): ByteArray { val size = input.readInt(); require(size in 0..32768 && size <= input.available()); return fixed(size) }
                val identity = TosQuantumSelfPayIdentity.fromBoc(field()); val state = field(); val authority = field()
                TosQuantumSelfPayIdentity.preflightOrdinaryBoc(authority, 16384)
                val value = Entry(operation, wallet, identity, profile, state, QuantumSelfPayAuthority.parse(BagOfCells(authority).roots.single()),
                    challenge, deadline, material, phase, field(), field())
                require(input.read() == -1); value
            }
        }
    }
    private val directory = File(context.noBackupFilesDir, "quantum-recovery-probes")
    private val outbox by lazy { QuantumPaymentOutbox(context) }
    private fun sync(file: File) {
        val fd = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    }
    private fun validate(fd: FileDescriptor) {
        val st = Os.fstat(fd)
        check(st.st_mode and OsConstants.S_IFMT == OsConstants.S_IFREG && st.st_uid == Os.getuid() && st.st_mode and 0x3f == 0 && st.st_nlink == 1L)
    }
    private fun file(identity: TosQuantumSelfPayIdentity) = File(directory,
        identity.cell.hash().toByteArray().joinToString("") { "%02x".format(it) } + ".qpp")
    private fun <T> locked(body: () -> T): T = synchronized(mutex) {
        try { Os.mkdir(directory.path, 0x1c0) } catch (e: ErrnoException) { if (e.errno != OsConstants.EEXIST) throw e }
        val st = Os.lstat(directory.path)
        check(st.st_mode and OsConstants.S_IFMT == OsConstants.S_IFDIR && st.st_uid == Os.getuid() && st.st_mode and 0x3f == 0)
        sync(directory); sync(context.noBackupFilesDir)
        val fd = Os.open(File(directory, ".lock").path, OsConstants.O_RDWR or OsConstants.O_CREAT or OsConstants.O_NOFOLLOW, 0x180)
        withOwnedOutputStream(fd) { stream ->
            validate(fd)
            val lease = try { stream.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            checkNotNull(lease).use {
                val names = checkNotNull(directory.list())
                check(names.size <= 129 && names.all { it == ".lock" || it.matches(Regex("[0-9a-f]{64}\\.qpp(?:\\.next)?")) })
                check(names.none { it.endsWith(".next") }) { "Probe write uncertain" }
                body()
            }
        }
    }
    private fun read(identity: TosQuantumSelfPayIdentity): Entry? {
        val fd = try { Os.open(file(identity).path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0) }
            catch (e: ErrnoException) { if (e.errno == OsConstants.ENOENT) return null else throw e }
        return withOwnedInputStream(fd) { input ->
            validate(fd); val size = Os.fstat(fd).st_size; check(size in 1..MAX_FILE.toLong())
            val bytes = ByteArray(size.toInt()); DataInputStream(input).readFully(bytes); check(input.read() == -1)
            Entry.decode(bytes).also { require(it.identity == identity) }
        }
    }
    private fun write(entry: Entry) {
        val target = file(entry.identity); val next = File(target.path + ".next")
        val fd = Os.open(next.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW, 0x180)
        withOwnedOutputStream(fd) { out -> validate(fd); out.write(entry.encode()); fault?.invoke("before_file_sync"); out.fd.sync() }
        fault?.invoke("before_rename"); Os.rename(next.path, target.path)
        fault?.invoke("before_directory_sync"); sync(directory)
    }
    fun pending(identity: TosQuantumSelfPayIdentity): Entry? = locked { read(identity) }
    fun reserve(plan: QuantumPreparedRecoveryProbe, materialDigest: ByteArray, now: Long): Entry = outbox.withRecoveryControlIsolation(plan.identity) {
        locked {
            plan.requireFresh(now); check(read(plan.identity) == null)
            check(checkNotNull(directory.list()).count { it.endsWith(".qpp") } < 64)
            Entry(UUID.randomUUID(), plan.walletId, plan.identity, plan.profile, plan.boundStateBOC, plan.successor,
                plan.challengeBytes, plan.expiresAt, materialDigest, Phase.RESERVED).also(::write)
        }
    }
    fun sign(identity: TosQuantumSelfPayIdentity, operation: UUID, fresh: QuantumAccountReader.Observation, now: Long,
             signer: (Entry) -> Pair<ByteArray, ByteArray>) = outbox.withRecoveryControlIsolation(identity) {
        locked {
            val entry = checkNotNull(read(identity)); check(entry.operation == operation && entry.phase == Phase.RESERVED)
            entry.requireCurrent(fresh, now)
            val signing = entry.next(Phase.SIGNING); write(signing)
            val signatures = signer(signing)
            try { write(signing.next(Phase.SIGNED, signatures)) }
            finally { signatures.first.fill(0); signatures.second.fill(0) }
        }
    }
    companion object { private val mutex = Any(); private const val MAX_FILE = 131072 }
}
