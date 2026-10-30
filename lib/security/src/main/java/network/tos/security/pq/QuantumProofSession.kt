package network.tos.security.pq

import android.content.Context
import android.system.Os
import android.system.ErrnoException
import android.system.OsConstants
import java.io.File
import java.util.UUID

/** Trusted local anchor and per-wallet checkpoint namespace. Results establish
 * proof verification only; wallet account/configuration binding remains required.
 * The native lock spans read, verification, file fsync and directory fsync.
 * Missing enrolled state refuses; enrolling again cannot repair it.
 */
class QuantumProofSession(context: Context, walletId: UUID, locallyProvisionedAnchor: ByteArray) {
    private val anchor = locallyProvisionedAnchor.also { require(it.size in 1..1_048_576) }.copyOf()
    private val root = privateDirectory(context.noBackupFilesDir, "quantum-proof-checkpoints")
    private val directory = privateDirectory(root, walletId.toString())

    /** Explicit first enrollment only. The caller must authenticate provisioning. */
    fun enroll(request: ByteArray, localNow: Long, kinds: IntArray, material: Array<ByteArray>): ByteArray =
        QuantumProofNative.verifyLivePersisted(directory.absolutePath, true, anchor, request, localNow, kinds, material)

    fun read(request: ByteArray, localNow: Long, kinds: IntArray, material: Array<ByteArray>): ByteArray =
        QuantumProofNative.verifyLivePersisted(directory.absolutePath, false, anchor, request, localNow, kinds, material)

    fun enroll(request: ByteArray, localNow: Long, transport: QuantumProofTransport): ByteArray =
        QuantumProofNative.acquireLivePersisted(directory.absolutePath, true, anchor, request, localNow, transport)

    fun read(request: ByteArray, localNow: Long, transport: QuantumProofTransport): ByteArray =
        QuantumProofNative.acquireLivePersisted(directory.absolutePath, false, anchor, request, localNow, transport)

    fun enrollBound(request: ByteArray, localNow: Long, transport: QuantumProofTransport): QuantumVerifiedRead =
        QuantumVerifiedRead.live(directory.absolutePath, true, anchor, request, localNow, transport)

    fun readBound(request: ByteArray, localNow: Long, transport: QuantumProofTransport): QuantumVerifiedRead =
        QuantumVerifiedRead.live(directory.absolutePath, false, anchor, request, localNow, transport)

    /** Remove an explicitly temporary session after its proof has been consumed. */
    fun removeCheckpoint() {
        val directoryFd = Os.open(directory.absolutePath,
            OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        try {
            val directoryStat = Os.fstat(directoryFd)
            check((directoryStat.st_mode and OsConstants.S_IFMT) == OsConstants.S_IFDIR &&
                directoryStat.st_uid == Os.getuid() && (directoryStat.st_mode and 0x3f) == 0) {
                "Checkpoint directory unavailable for cleanup"
            }
            // The native persisted session owns these three files. A successful
            // enrollment leaves its lock and initialization marker behind too.
            // This method is only for exclusively owned, completed temporary sessions.
            val names = QuantumLocalDirectory.names(directory.absolutePath, 4)
            check(names.all { it in setOf("checkpoint.json", "checkpoint.lock", "initialized") }) {
                "Unexpected file in temporary checkpoint directory"
            }
            for (name in names) {
                val fd = Os.open(File(directory, name).absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
                try {
                    val stat = Os.fstat(fd)
                    val validSize = when (name) {
                        "checkpoint.json" -> stat.st_size in 1..1_048_576
                        "checkpoint.lock" -> stat.st_size == 0L
                        else -> stat.st_size == 1L
                    }
                    check((stat.st_mode and OsConstants.S_IFMT) == OsConstants.S_IFREG &&
                        stat.st_uid == Os.getuid() && (stat.st_mode and 0x3f) == 0 && validSize) {
                        "Checkpoint file unavailable for cleanup"
                    }
                } finally { Os.close(fd) }
            }
            for (name in names) Os.remove(File(directory, name).absolutePath)
            Os.fsync(directoryFd)
        } finally { Os.close(directoryFd) }
        check(directory.delete()) { "Temporary checkpoint directory is not empty" }
        val rootFd = Os.open(root.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        try {
            val rootStat = Os.fstat(rootFd)
            check((rootStat.st_mode and OsConstants.S_IFMT) == OsConstants.S_IFDIR &&
                rootStat.st_uid == Os.getuid() && (rootStat.st_mode and 0x3f) == 0)
            Os.fsync(rootFd)
        } finally { Os.close(rootFd) }
    }

    /** Requires an existing enrolled checkpoint and commits its successor before returning an estimate. */
    fun simulate(request: ByteArray, localNow: Long, signedMessage: ByteArray, transport: QuantumProofTransport): QuantumLocalSimulation =
        QuantumSimulationNative.simulate(directory.absolutePath, anchor, request, localNow, signedMessage, transport)

    private fun privateDirectory(parent: File, name: String): File {
        val result = File(parent, name)
        try { Os.mkdir(result.absolutePath, 0x1c0) } // 0700, atomically private
        catch (error: ErrnoException) { if (error.errno != OsConstants.EEXIST) throw error }
        val info = Os.lstat(result.absolutePath)
        check((info.st_mode and OsConstants.S_IFMT) == OsConstants.S_IFDIR &&
              info.st_uid == Os.getuid() && (info.st_mode and 0x3f) == 0) { "Checkpoint directory unavailable" }
        return result
    }
}
