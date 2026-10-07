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
class V5R2ProofSession(context: Context, walletId: UUID, locallyProvisionedAnchor: ByteArray) {
    private val anchor = locallyProvisionedAnchor.also { require(it.size in 1..1_048_576) }.copyOf()
    private val directory = privateDirectory(privateDirectory(context.noBackupFilesDir, "v5r2-proof-checkpoints"), walletId.toString())

    /** Explicit first enrollment only. The caller must authenticate provisioning. */
    fun enroll(request: ByteArray, localNow: Long, kinds: IntArray, material: Array<ByteArray>): ByteArray =
        V5R2ProofNative.verifyLivePersisted(directory.absolutePath, true, anchor, request, localNow, kinds, material)

    fun read(request: ByteArray, localNow: Long, kinds: IntArray, material: Array<ByteArray>): ByteArray =
        V5R2ProofNative.verifyLivePersisted(directory.absolutePath, false, anchor, request, localNow, kinds, material)

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
