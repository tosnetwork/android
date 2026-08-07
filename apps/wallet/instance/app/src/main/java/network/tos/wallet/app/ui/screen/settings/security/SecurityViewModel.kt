package network.tos.wallet.app.ui.screen.settings.security

import android.app.Application
import android.content.Context
import network.tos.wallet.app.core.FirebaseHelper
import network.tos.wallet.app.extensions.isSafeModeEnabled
import network.tos.wallet.app.ui.base.BaseWalletVM
import network.tos.wallet.api.API
import network.tos.wallet.data.passcode.PasscodeManager
import network.tos.wallet.data.rn.RNLegacy
import network.tos.wallet.data.settings.SafeModeState
import network.tos.wallet.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

class SecurityViewModel(
    app: Application,
    private val settingsRepository: SettingsRepository,
    private val rnLegacy: RNLegacy,
    private val passcodeManager: PasscodeManager,
    private val api: API
): BaseWalletVM(app) {

    var lockScreen: Boolean
        get() = settingsRepository.lockScreen
        set(value) {
            settingsRepository.lockScreen = value
        }

    val biometric: Boolean
        get() = settingsRepository.biometric

    val safeModeFlow: Flow<SafeModeState>
        get() = settingsRepository.safeModeStateFlow

    fun isSafeModeEnabled() = settingsRepository.isSafeModeEnabled(api)

    fun setSafeModeState(state: SafeModeState) {
        settingsRepository.setSafeModeState(state)
        FirebaseHelper.secureModeEnabled(state)
    }

    fun enableBiometric(context: Context, value: Boolean) = flow {
        if (value) {
            val code = passcodeManager.requestValidPasscode(context)
            rnLegacy.setupBiometry(code)
        } else {
            rnLegacy.removeBiometry()
        }
        settingsRepository.biometric = value
        emit(Unit)
    }.flowOn(Dispatchers.IO)
}