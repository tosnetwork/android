package com.tonapps.wallet.api

import android.os.SystemClock
import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.tonapps.network.OkHttpError
import io.infrastructure.ClientError
import io.infrastructure.ClientException
import io.infrastructure.ServerError
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import java.net.SocketTimeoutException

fun <R> withRetry(
    times: Int = 5,
    delay: Long = 500,
    retryBlock: () -> R
): R? {
    var index = -1
    do {
        index++
        try {
            return retryBlock()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            Log.e("RetryLogNew", "SocketTimeoutException occurred: ${e.message}", e)
            SystemClock.sleep(delay + 100)
            return null
        } catch (e: IOException) {
            Log.e("RetryLogNew", "IOException occurred: ${e.message}", e)
            SystemClock.sleep(delay + 100)
            return null
        } catch (e: Throwable) {
            Log.e("RetryLogNew", "Error occurred: ${e.message}", e)
            val statusCode = e.getHttpStatusCode()
            if (statusCode == 429 || statusCode == 401 || statusCode == 502 || statusCode == 520) {
                SystemClock.sleep(delay + 100)
                continue
            }
            if (statusCode >= 500 || statusCode == 404 || statusCode == 400) {
                return null
            }
            FirebaseCrashlytics.getInstance().recordException(e)
        }

    } while (index < times)
    return null
}

private fun Throwable.getHttpStatusCode(): Int {
    return when (this) {
        is ClientException -> statusCode
        is OkHttpError -> statusCode
        else -> 0
    }
}

fun Throwable.getDebugMessage(): String? {
    return when (this) {
        is ClientException -> getHttpBodyMessage()
        is OkHttpError -> body
        else -> message
    }
}

private fun ClientException.getHttpBodyMessage(): String {
    return when (response) {
        is ClientError<*> -> (response as ClientError<*>).body.toString()
        is ServerError<*> -> (response as ServerError<*>).body.toString()
        else -> response.toString()
    }
}