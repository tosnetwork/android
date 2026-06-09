package com.tonapps.wallet.api

import android.content.Context
import android.os.SystemClock
import com.tonapps.extensions.prefs
import androidx.core.content.edit

internal class ServerTimeProvider(context: Context) {

    private companion object {
        const val SERVER_TIME_KEY = "server_time"
        const val LOCAL_TIME_KEY = "local_time"

        // 24 hours in milliseconds
        private const val CACHE_EXPIRATION_MS = 24 * 60 * 60 * 1000L

        private fun getServerTimePrefKey(testnet: Boolean) = "${SERVER_TIME_KEY}_${if (testnet) "test" else "main"}"

        private fun getLocalTimePrefKey(testnet: Boolean) = "${LOCAL_TIME_KEY}_${if (testnet) "test" else "main"}"

    }

    private val prefs = context.prefs("server_time")

    fun setServerTime(testnet: Boolean, serverTimeSeconds: Int) {
        val localTimeMillis = SystemClock.elapsedRealtime()

        prefs.edit {
            putInt(getServerTimePrefKey(testnet), serverTimeSeconds)
            putLong(getLocalTimePrefKey(testnet), localTimeMillis)
        }
    }

    fun getServerTime(testnet: Boolean): Int? {
        val savedServerSeconds = prefs.getInt(getServerTimePrefKey(testnet), 0)
        val savedLocalMillis = prefs.getLong(getLocalTimePrefKey(testnet), 0L)
        if (0 >= savedServerSeconds || 0 >= savedLocalMillis) {
            return null
        }
        val elapsedTimeMillis = SystemClock.elapsedRealtime() - savedLocalMillis
        if (elapsedTimeMillis > CACHE_EXPIRATION_MS) {
            return null
        }
        val elapsedSeconds = elapsedTimeMillis / 1000
        val currentServerTime = savedServerSeconds + elapsedSeconds
        return currentServerTime.toInt()
    }
}