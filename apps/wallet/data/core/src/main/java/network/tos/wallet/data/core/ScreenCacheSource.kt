package network.tos.wallet.data.core

import android.content.Context
import android.os.Parcel
import android.os.Parcelable
import android.util.Log
import network.tos.extensions.CrashReporter
import network.tos.extensions.cacheFolder
import network.tos.extensions.file
import network.tos.extensions.folder
import network.tos.extensions.toByteArray
import network.tos.extensions.toListParcel
import java.io.File
import kotlin.math.min

class ScreenCacheSource(
    context: Context
) {

    private val rootFolder = context.cacheFolder("screen")

    inline fun <reified T: Parcelable> get(
        name: String,
        walletId: String,
        block: (parcel: Parcel) -> T
    ): List<T> {
        val bytes = getData(name, walletId)
        if (bytes.isEmpty()) {
            return emptyList()
        }
        val l = bytes.toListParcel(block) ?: emptyList()
        return l
    }

    fun getData(
        name: String,
        walletId: String,
    ): ByteArray {
        try {
            val file = getFile(name, walletId)
            if (!file.exists() || file.length() == 0L || !file.canRead()) {
                return byteArrayOf()
            }
            return file.readBytes()
        } catch (e: Throwable) {
            CrashReporter.recordException(e)
            return byteArrayOf()
        }
    }

    fun set(
        name: String,
        walletId: String,
        list: List<Parcelable>
    ) {
        val file = getFile(name, walletId)
        if (list.isEmpty()) {
            file.delete()
        } else {
            val maxListSize = min(list.size, 25)
            val bytes = list.subList(0, maxListSize).toByteArray()
            // val bytes = list.toByteArray()
            file.writeBytes(bytes)
        }
    }

    private fun getFolder(name: String): File {
        return rootFolder.folder(name)
    }

    private fun getFile(name: String, walletId: String): File {
        val folder = getFolder(name)
        val filename = "$walletId.dat"
        return folder.file(filename)
    }
}