package com.tonapps.wallet.api

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.roundToInt

class FileDownloader(private val okHttpClient: OkHttpClient) {

    sealed class DownloadStatus {
        data class Progress(
            val downloadedBytes: Long = 0,
            val totalBytes: Long = 0,
            val percent: Int = 0,
            val downloadSpeed: String = ""
        ) : DownloadStatus()

        data class Error(val throwable: Throwable) : DownloadStatus()

        data class Success(val file: File) : DownloadStatus()
    }

    fun download(
        url: String,
        outputFile: File,
        bufferSize: Int = DEFAULT_BUFFER_SIZE
    ) = callbackFlow {
        // Go through the shared OkHttp client so the download inherits the app's TLS
        // configuration (including certificate pinning) instead of a raw, unpinned
        // HttpURLConnection.
        val call = okHttpClient.newCall(Request.Builder().url(url).get().build())

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP error code: ${response.code}")
                }
                val body = response.body ?: throw IOException("Empty response body")

                val contentLength = body.contentLength()
                var downloadedBytes = 0L
                var lastEmitTime = System.currentTimeMillis()
                var bytesFromLastEmit = 0L

                body.byteStream().use { input ->
                    FileOutputStream(outputFile).use { output ->
                        val buffer = ByteArray(bufferSize)
                        var bytes = input.read(buffer)

                        while (bytes >= 0) {
                            output.write(buffer, 0, bytes)
                            downloadedBytes += bytes
                            bytesFromLastEmit += bytes

                            val currentTime = System.currentTimeMillis()
                            val timeElapsed = currentTime - lastEmitTime

                            if (timeElapsed >= 100) {
                                val speedBytesPerSec = (bytesFromLastEmit * 1000.0 / timeElapsed).roundToInt()
                                val progress = DownloadStatus.Progress(
                                    downloadedBytes = downloadedBytes,
                                    totalBytes = contentLength,
                                    percent = if (contentLength > 0) {
                                        (downloadedBytes * 100 / contentLength).toInt()
                                    } else 0,
                                    downloadSpeed = formatSpeed(speedBytesPerSec)
                                )
                                trySend(progress)

                                lastEmitTime = currentTime
                                bytesFromLastEmit = 0
                            }

                            bytes = input.read(buffer)
                        }
                    }
                }
            }

            trySend(DownloadStatus.Success(outputFile))

        } catch (e: Exception) {
            trySend(DownloadStatus.Error(e))
            outputFile.delete()
        }

        awaitClose { call.cancel() }
    }

    private fun formatSpeed(bytesPerSec: Int): String {
        return when {
            bytesPerSec >= 1_000_000 -> "%.1f MB/s".format(bytesPerSec / 1_000_000.0)
            bytesPerSec >= 1_000 -> "%.1f KB/s".format(bytesPerSec / 1_000.0)
            else -> "$bytesPerSec B/s"
        }
    }

    companion object {
        private const val DEFAULT_BUFFER_SIZE = 8192 // 8KB buffer
    }
}
