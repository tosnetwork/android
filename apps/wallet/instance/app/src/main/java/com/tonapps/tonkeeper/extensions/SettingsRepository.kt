package com.tonapps.tonkeeper.extensions

import com.tonapps.wallet.api.API
import com.tonapps.wallet.data.settings.SafeModeState
import com.tonapps.wallet.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.util.Locale

fun SettingsRepository.isSafeModeEnabled(api: API): Boolean {
    val state = getSafeModeState()
    if (state == SafeModeState.Default) {
        return api.config.flags.safeModeEnabled
    }
    return state == SafeModeState.Enabled
}