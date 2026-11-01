package network.tos.wallet.data.account.pq

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Ciphertext only. A staged file is never a completed user backup. */
class QuantumBackupExportFile internal constructor(
    private val directory: File,
    private val active: MutableSet<UUID> = processActive,
    private val fault: ((String) -> Unit)? = null,
) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "quantum-backup-export"))
    class Ticket internal constructor(internal val id: UUID, internal val size: Int,
                                     internal val digest: ByteArray, internal val owner: String)

    private fun prepare() {
        try { Os.mkdir(directory.absolutePath, 0x1c0) }
        catch (e: ErrnoException) { if (e.errno != OsConstants.EEXIST) throw e }
        val st = Os.lstat(directory.absolutePath)
        check(st.st_mode and OsConstants.S_IFMT == OsConstants.S_IFDIR && st.st_uid == Os.getuid() && st.st_mode and 0x3f == 0)
        val parent = Os.open(directory.parentFile!!.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        try { Os.fsync(parent) } finally { Os.close(parent) }
    }
    private fun sync() {
        val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    }
    private fun path(id: UUID, suffix: String) = File(directory, "$id.$suffix")
    private fun regular(file: File) {
        val st = Os.lstat(file.absolutePath)
        check(st.st_mode and OsConstants.S_IFMT == OsConstants.S_IFREG && st.st_uid == Os.getuid() &&
            st.st_mode and 0x3f == 0 && st.st_nlink == 1L)
    }
    private fun sweep() {
        val files = checkNotNull(directory.listFiles())
        check(files.size <= 4) { "Export staging capacity exceeded" }
        var changed = false
        for (file in files) {
            require(file.name.matches(Regex("[0-9a-f-]{36}\\.(next|ready)")))
            val id = UUID.fromString(file.name.substringBefore('.'))
            regular(file)
            if (id !in active) { Os.remove(file.absolutePath); changed = true }
        }
        if (changed) sync()
    }

    fun stage(encrypted: ByteArray): Ticket = synchronized(lock) {
        require(encrypted.size in 58..262201)
        prepare(); sweep()
        check(active.size < 2) { "Another export must finish first" }
        val id = UUID.randomUUID()
        check(active.add(id))
        val pending = path(id, "next"); val ready = path(id, "ready")
        val owned = encrypted.copyOf()
        var created = false
        try {
            val fd = Os.open(pending.absolutePath, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW, 0x180)
            created = true
            FileOutputStream(fd).use { output ->
                output.write(owned)
                fault?.invoke("before_file_sync")
                output.fd.sync()
            }
            Os.rename(pending.absolutePath, ready.absolutePath)
            fault?.invoke("before_directory_sync")
            sync()
            Ticket(id, owned.size, MessageDigest.getInstance("SHA-256").digest(owned), directory.absolutePath)
        } catch (error: Throwable) {
            active.remove(id)
            if (created) {
                try {
                    for (file in listOf(pending, ready)) if (file.exists()) { regular(file); Os.remove(file.absolutePath) }
                    sync()
                } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            }
            throw error
        } finally { owned.fill(0) }
    }

    fun read(ticket: Ticket): ByteArray = synchronized(lock) {
        require(ticket.owner == directory.absolutePath && ticket.id in active)
        prepare()
        val file = path(ticket.id, "ready")
        regular(file)
        val fd = Os.open(file.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        FileInputStream(fd).use { input ->
            val st = Os.fstat(fd)
            check(st.st_size == ticket.size.toLong() && st.st_size in 58..262201 && st.st_nlink == 1L &&
                st.st_mode and OsConstants.S_IFMT == OsConstants.S_IFREG && st.st_uid == Os.getuid() && st.st_mode and 0x3f == 0)
            val bytes = ByteArray(ticket.size)
            try {
                var offset = 0
                while (offset < bytes.size) {
                    val count = input.read(bytes, offset, bytes.size - offset)
                    check(count > 0); offset += count
                }
                check(input.read() == -1 && MessageDigest.isEqual(ticket.digest, MessageDigest.getInstance("SHA-256").digest(bytes)))
                bytes
            } catch (error: Throwable) { bytes.fill(0); throw error }
        }
    }

    fun discard(ticket: Ticket) = synchronized(lock) {
        require(ticket.owner == directory.absolutePath)
        prepare()
        val file = path(ticket.id, "ready")
        try { regular(file); Os.remove(file.absolutePath); sync() }
        catch (e: ErrnoException) { if (e.errno != OsConstants.ENOENT) throw e }
        finally { active.remove(ticket.id) }
    }

    fun saveAndVerify(ticket: Ticket, resolver: ContentResolver, uri: Uri) {
        require(uri.scheme == "content")
        writeAndVerify(ticket, { checkNotNull(resolver.openFileDescriptor(uri, "wt")) },
            { checkNotNull(resolver.openInputStream(uri)) })
    }

    internal fun writeAndVerify(ticket: Ticket, openWrite: () -> ParcelFileDescriptor, openRead: () -> InputStream) {
        check(writing.compareAndSet(false, true)) { "Another document save is active" }
        var bytes = ByteArray(0)
        try {
            bytes = read(ticket)
            openWrite().use { descriptor ->
                ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                    output.write(bytes); output.flush(); output.fd.sync(); descriptor.checkError()
                }
            }
            val hash = MessageDigest.getInstance("SHA-256")
            var total = 0
            openRead().use { input ->
                val buffer = ByteArray(8192)
                while (total <= bytes.size) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val count = input.read(buffer, 0, minOf(buffer.size, bytes.size + 1 - total))
                    if (count < 0) break
                    check(count > 0); hash.update(buffer, 0, count); total += count
                }
            }
            check(total == bytes.size && MessageDigest.isEqual(ticket.digest, hash.digest())) { "Saved backup could not be verified" }
        } finally { bytes.fill(0); writing.set(false) }
    }

    companion object {
        private val lock = Any()
        private val processActive = mutableSetOf<UUID>()
        private val writing = AtomicBoolean(false)
    }
}
