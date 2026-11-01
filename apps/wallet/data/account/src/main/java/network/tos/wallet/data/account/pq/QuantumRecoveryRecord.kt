package network.tos.wallet.data.account.pq

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import network.tos.blockchain.ton.contract.TosQuantumSelfPayIdentity
import network.tos.wallet.data.account.entities.WalletIdentity
import org.json.JSONObject
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

/** Public import enrollment only. Its existence never authorizes signing or clears isolation. */
internal class QuantumRecoveryRecord(
    val id: UUID,
    val profile: QuantumNetworkProfile,
    val identity: TosQuantumSelfPayIdentity,
    digest: ByteArray,
) {
    private val digest = digest.copyOf()
    val encryptedDigest: ByteArray get() = digest.copyOf()
    init {
        require(digest.size == 32 && identity.wallet.initialAuthority.globalId == profile.globalId)
    }
    private fun encode(): ByteArray = JSONObject().put("version", 1).put("id", id.toString())
        .put("mode", "recovery_only").put("profile", profile.json())
        .put("identity", WalletIdentity.Quantum(identity).toBase64())
        .put("encrypted_digest", digest.joinToString("") { "%02x".format(it.toInt() and 255) })
        .toString().toByteArray(Charsets.UTF_8).also { require(it.size in 1..32768) }

    /** Same binding retries sync the existing inode; conflicting or torn records stay blocked. */
    fun save(context: Context) = synchronized(lock) {
        val directory = directory(context)
        val path = File(directory, "$id.json")
        val bytes = encode()
        val names = checkNotNull(directory.list())
        check(names.size < 64 || names.size == 64 && names.contains(path.name)) { "Recovery enrollment capacity" }
        val fd = try {
            Os.open(path.absolutePath, OsConstants.O_WRONLY or OsConstants.O_CREAT or
                OsConstants.O_EXCL or OsConstants.O_NOFOLLOW, 0x180)
        } catch (error: ErrnoException) {
            if (error.errno != OsConstants.EEXIST) throw error
            val retained = load(context, id)
            check(retained.id == id && retained.identity == identity &&
                retained.profile.globalId == profile.globalId && retained.profile.rootHash == profile.rootHash &&
                retained.profile.fileHash == profile.fileHash && retained.digest.contentEquals(digest)) {
                "Conflicting recovery enrollment"
            }
            val existing = Os.open(path.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
            try { validate(existing); Os.fsync(existing) } finally { Os.close(existing) }
            syncDirectory(directory)
            return@synchronized
        }
        FileOutputStream(fd).use { output -> output.write(bytes); output.fd.sync() }
        syncDirectory(directory)
    }

    companion object {
        private val lock = Any()
        /** A surviving public recovery-only enrollment is itself an isolation obligation. */
        fun containsIdentity(context: Context, identity: TosQuantumSelfPayIdentity): Boolean = synchronized(lock) {
            val path = File(context.noBackupFilesDir, "quantum-recovery-records")
            try { Os.lstat(path.absolutePath) }
            catch (error: ErrnoException) { if (error.errno == OsConstants.ENOENT) return@synchronized false else throw error }
            val names = checkNotNull(directory(context).list())
            check(names.size <= 64) { "Recovery enrollment capacity" }
            var found = false
            for (name in names) {
                check(name.endsWith(".json"))
                val id = UUID.fromString(name.removeSuffix(".json"))
                check(name == "$id.json")
                if (load(context, id).identity == identity) found = true
            }
            found
        }
        private fun validate(fd: java.io.FileDescriptor) {
            val stat = Os.fstat(fd)
            check(stat.st_mode and OsConstants.S_IFMT == OsConstants.S_IFREG &&
                stat.st_uid == Os.getuid() && stat.st_nlink == 1L && stat.st_mode and 0x3f == 0 &&
                stat.st_size in 1..32768L) { "Recovery enrollment unavailable" }
        }
        private fun directory(context: Context): File {
            val path = File(context.noBackupFilesDir, "quantum-recovery-records")
            try { Os.mkdir(path.absolutePath, 0x1c0) }
            catch (error: ErrnoException) { if (error.errno != OsConstants.EEXIST) throw error }
            val stat = Os.lstat(path.absolutePath)
            check(stat.st_mode and OsConstants.S_IFMT == OsConstants.S_IFDIR &&
                stat.st_uid == Os.getuid() && stat.st_mode and 0x3f == 0)
            syncDirectory(context.noBackupFilesDir)
            return path
        }
        private fun syncDirectory(path: File) {
            val fd = Os.open(path.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
            try {
                check(Os.fstat(fd).st_mode and OsConstants.S_IFMT == OsConstants.S_IFDIR)
                Os.fsync(fd)
            } finally { Os.close(fd) }
        }
        fun load(context: Context, id: UUID): QuantumRecoveryRecord = synchronized(lock) {
            val fd = Os.open(File(directory(context), "$id.json").absolutePath,
                OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
            val bytes = FileInputStream(fd).use { input ->
                validate(fd)
                ByteArray(Os.fstat(fd).st_size.toInt()).also {
                    DataInputStream(input).readFully(it); check(input.read() == -1)
                }
            }
            val value = JSONObject(bytes.toString(Charsets.UTF_8))
            require(value.keys().asSequence().toSet() == setOf("version", "id", "mode", "profile", "identity", "encrypted_digest"))
            require(value.get("version") == 1 && value.get("mode") == "recovery_only" && value.get("id") == id.toString())
            val hex = value.getString("encrypted_digest")
            require(hex.matches(Regex("[0-9a-f]{64}")))
            QuantumRecoveryRecord(id, QuantumNetworkProfile.parse(value.getJSONObject("profile")),
                WalletIdentity.quantumFromBase64(value.getString("identity")),
                hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        }
    }
}
