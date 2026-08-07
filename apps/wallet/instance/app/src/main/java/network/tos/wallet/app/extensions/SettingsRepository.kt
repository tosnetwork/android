package network.tos.wallet.app.extensions

import network.tos.wallet.api.API
import network.tos.wallet.data.settings.SafeModeState
import network.tos.wallet.data.settings.SettingsRepository
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