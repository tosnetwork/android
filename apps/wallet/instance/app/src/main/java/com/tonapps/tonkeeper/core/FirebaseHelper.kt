package com.tonapps.tonkeeper.core

import com.tonapps.wallet.data.settings.SafeModeState

/**
 * TOS: Firebase Analytics has been removed — these events are not sent anywhere.
 * Kept as no-op drop-ins so existing call sites compile unchanged.
 */
object FirebaseHelper {

    fun secureModeEnabled(state: SafeModeState) {}

    fun trc20Enabled(enabled: Boolean) {}

    fun searchEngine(value: String) {}

    fun setTitleEmoji(emoji: String) {}
}
