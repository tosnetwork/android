package com.tonapps.extensions

import android.util.Log

/**
 * TOS: local, no-op replacement for the former Firebase Crashlytics reporter.
 *
 * First principles: the wallet sends no crash/telemetry data to any external
 * service. This keeps existing `recordException` call sites working as a drop-in
 * (`FirebaseCrashlytics.getInstance()` -> `CrashReporter`) while reporting only
 * to the local logcat.
 */
object CrashReporter {

    fun recordException(throwable: Throwable) {
        Log.w("CrashReporter", "recorded exception", throwable)
    }

    fun log(message: String) {
        Log.w("CrashReporter", message)
    }

    fun setCustomKey(key: String, value: String) {}

    fun setCustomKey(key: String, value: Boolean) {}

    fun setCustomKey(key: String, value: Int) {}

    fun setCustomKey(key: String, value: Long) {}
}
