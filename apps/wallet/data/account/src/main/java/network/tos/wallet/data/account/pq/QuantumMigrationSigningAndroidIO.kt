package network.tos.wallet.data.account.pq

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor
import java.nio.channels.OverlappingFileLockException

/** Private, no-backup storage. The key is the canonical birth identity hash, not a wallet label. */
internal class QuantumMigrationSigningAndroidIO(context: Context, private val birthHash: String) : QuantumMigrationSigningStore.IO {
    private val directory = File(context.noBackupFilesDir, "quantum-migration-signing")
    private val current get() = File(directory, "$birthHash.record")
    private val pending get() = File(directory, "$birthHash.next")
    init { require(birthHash.matches(Regex("[0-9a-f]{64}"))) }

    override fun <T> locked(body: () -> T): T = synchronized(lock) {
        prepare()
        val fd = Os.open(File(directory, ".lock").absolutePath,
            OsConstants.O_RDWR or OsConstants.O_CREAT or OsConstants.O_NOFOLLOW, 0x180)
        withOwnedOutputStream(fd) { stream ->
            validate(fd, 0, 0)
            val held = try { stream.channel.tryLock() }
                catch (_: OverlappingFileLockException) { null }
            checkNotNull(held) { "Another migration storage operation is active" }
            try { body() } finally { held.release() }
        }
    }

    private fun prepare() {
        try { Os.mkdir(directory.absolutePath, 0x1c0) }
        catch (error: ErrnoException) { if (error.errno != OsConstants.EEXIST) throw error }
        val st = Os.lstat(directory.absolutePath)
        check(st.st_mode and OsConstants.S_IFMT == OsConstants.S_IFDIR && st.st_uid == Os.getuid() && st.st_mode and 0x3f == 0)
        val parent = Os.open(checkNotNull(directory.parentFile).absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        try { Os.fsync(parent) } finally { Os.close(parent) }
        val names = checkNotNull(directory.list())
        check(names.size <= 129)
        for (name in names) {
            check(name == ".lock" || name.matches(Regex("[0-9a-f]{64}\\.(record|next)")))
            val entry = Os.lstat(File(directory, name).absolutePath)
            check(entry.st_mode and OsConstants.S_IFMT == OsConstants.S_IFREG && entry.st_uid == Os.getuid() &&
                entry.st_nlink == 1L && entry.st_mode and 0x3f == 0 && entry.st_size in 0..QuantumMigrationSigningRecord.MAX_FILE_BYTES.toLong())
        }
    }

    private fun validate(fd: FileDescriptor, minimum: Long, maximum: Long) {
        val st = Os.fstat(fd)
        check(st.st_mode and OsConstants.S_IFMT == OsConstants.S_IFREG && st.st_uid == Os.getuid() &&
            st.st_nlink == 1L && st.st_mode and 0x3f == 0 && st.st_size in minimum..maximum)
    }

    override fun hasPending(): Boolean = try { Os.lstat(pending.absolutePath); true }
        catch (error: ErrnoException) { if (error.errno == OsConstants.ENOENT) false else throw error }

    override fun readCurrent(): ByteArray? {
        val fd = try { Os.open(current.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0) }
        catch (error: ErrnoException) { if (error.errno == OsConstants.ENOENT) return null else throw error }
        return withOwnedInputStream(fd) { input ->
            validate(fd, 30, QuantumMigrationSigningRecord.MAX_FILE_BYTES.toLong())
            val size = Os.fstat(fd).st_size
            check(size in 30..QuantumMigrationSigningRecord.MAX_FILE_BYTES.toLong())
            val bytes = ByteArray(size.toInt())
            var offset = 0
            while (offset < bytes.size) { val count = input.read(bytes, offset, bytes.size - offset); check(count > 0); offset += count }
            check(input.read() == -1)
            bytes
        }
    }

    override fun writePending(bytes: ByteArray) {
        require(bytes.size in 30..QuantumMigrationSigningRecord.MAX_FILE_BYTES)
        val slots = checkNotNull(directory.list()).filter { it != ".lock" }.map { it.substringBefore('.') }.toSet()
        check(birthHash in slots || slots.size < 64) { "Migration slot capacity exceeded" }
        val fd = Os.open(pending.absolutePath, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW, 0x180)
        withOwnedOutputStream(fd) { output -> validate(fd, 0, 0); output.write(bytes); output.fd.sync() }
    }

    override fun replacePending() { Os.rename(pending.absolutePath, current.absolutePath) }
    override fun syncCurrent() {
        val fd = Os.open(current.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        try { validate(fd, 30, QuantumMigrationSigningRecord.MAX_FILE_BYTES.toLong()); Os.fsync(fd) } finally { Os.close(fd) }
    }
    override fun syncDirectory() {
        val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    }
    companion object { private val lock = Any() }
}
