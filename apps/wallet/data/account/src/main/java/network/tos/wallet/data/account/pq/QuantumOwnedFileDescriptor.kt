package network.tos.wallet.data.account.pq

import android.system.Os
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream

/** Android streams constructed from an existing descriptor borrow it; callers retain ownership. */
internal inline fun <T> withOwnedDescriptor(descriptor: FileDescriptor, body: (FileDescriptor) -> T): T {
    var failure: Throwable? = null
    try { return body(descriptor) }
    catch (error: Throwable) { failure = error; throw error }
    finally {
        try { if (descriptor.valid()) Os.close(descriptor) }
        catch (closing: Throwable) {
            val original = failure
            if (original == null) throw closing
            if (original !== closing) original.addSuppressed(closing)
        }
    }
}

internal inline fun <T> withOwnedInputStream(descriptor: FileDescriptor, body: (FileInputStream) -> T): T =
    withOwnedDescriptor(descriptor) { FileInputStream(it).use(body) }

internal inline fun <T> withOwnedOutputStream(descriptor: FileDescriptor, body: (FileOutputStream) -> T): T =
    withOwnedDescriptor(descriptor) { FileOutputStream(it).use(body) }
