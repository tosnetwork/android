package com.tonapps.tonkeeper.manager.push

/**
 * TOS: Firebase Cloud Messaging has been removed — the wallet does not register a
 * push token with Google or receive FCM messages. This is kept only so existing
 * callers of `requestToken()` compile; it always returns null (no push token).
 */
object FirebasePush {

    suspend fun requestToken(): String? = null
}
