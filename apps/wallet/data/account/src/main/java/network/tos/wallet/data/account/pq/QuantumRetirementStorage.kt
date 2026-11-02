package network.tos.wallet.data.account.pq

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import network.tos.security.pq.QuantumLocalDirectory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** All callers already hold the outbox's shared mutex and flock. Faults never imply rollback. */
internal class QuantumRetirementStorage(private val fault: ((String) -> Unit)?) {
    fun point(name: String) { fault?.invoke(name) }
    fun exists(file: File): Boolean = try { Os.lstat(file.absolutePath); true }
        catch (error: ErrnoException) { if (error.errno == OsConstants.ENOENT) false else throw error }
    fun names(directory: File, maximum: Int) = QuantumLocalDirectory.names(directory.absolutePath, maximum)
    fun directory(directory: File) {
        try { Os.mkdir(directory.absolutePath, 0x1c0) }
        catch (error: ErrnoException) { if (error.errno != OsConstants.EEXIST) throw error }
        val st = Os.lstat(directory.absolutePath)
        check(st.st_mode and OsConstants.S_IFMT == OsConstants.S_IFDIR && st.st_uid == Os.getuid() && st.st_mode and 0x3f == 0)
    }
    fun read(file: File, maximum: Int, allowEmpty: Boolean = false): ByteArray {
        val fd = Os.open(file.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        return FileInputStream(fd).use { input ->
            val st = Os.fstat(fd)
            check(st.st_mode and OsConstants.S_IFMT == OsConstants.S_IFREG && st.st_uid == Os.getuid() &&
                st.st_mode and 0x3f == 0 && st.st_nlink == 1L && st.st_size in (if (allowEmpty) 0L else 1L)..maximum.toLong())
            val bytes = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                check(count <= maximum - bytes.size()); bytes.write(buffer, 0, count)
            }
            bytes.toByteArray()
        }
    }
    fun create(file: File, bytes: ByteArray, maximum: Int, label: String) {
        require(bytes.size in 1..maximum)
        point("$label.before_open")
        val fd = Os.open(file.absolutePath, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW, 0x180)
        FileOutputStream(fd).use { stream ->
            point("$label.before_write"); stream.write(bytes)
            point("$label.before_file_sync"); stream.fd.sync(); point("$label.after_file_sync")
        }
    }
    fun sync(directory: File, label: String) {
        point("$label.before_dir_sync")
        val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        try {
            val st = Os.fstat(fd)
            check(st.st_mode and OsConstants.S_IFMT == OsConstants.S_IFDIR && st.st_uid == Os.getuid() && st.st_mode and 0x3f == 0)
            Os.fsync(fd)
        } finally { Os.close(fd) }
        point("$label.after_dir_sync")
    }
    fun syncFile(file: File, maximum: Int, label: String) {
        val fd = Os.open(file.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        try {
            val st = Os.fstat(fd)
            check(st.st_mode and OsConstants.S_IFMT == OsConstants.S_IFREG && st.st_uid == Os.getuid() &&
                st.st_mode and 0x3f == 0 && st.st_nlink == 1L && st.st_size in 1..maximum.toLong())
            point("$label.before_file_sync"); Os.fsync(fd); point("$label.after_file_sync")
        } finally { Os.close(fd) }
    }
    fun atomic(file: File, bytes: ByteArray, maximum: Int, label: String) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        if (exists(temporary)) {
            read(temporary, maximum, allowEmpty = true) // Known partial public metadata, never a symlink/directory or oversized file.
            point("$label.before_old_temp_unlink"); Os.remove(temporary.absolutePath)
        }
        create(temporary, bytes, maximum, label)
        point("$label.before_rename"); Os.rename(temporary.absolutePath, file.absolutePath)
        point("$label.after_rename"); sync(file.parentFile!!, label)
    }
    fun unlink(file: File, label: String) {
        point("$label.before_unlink")
        try { Os.remove(file.absolutePath) }
        catch (error: ErrnoException) { if (error.errno != OsConstants.ENOENT) throw error }
        point("$label.after_unlink")
    }
}
