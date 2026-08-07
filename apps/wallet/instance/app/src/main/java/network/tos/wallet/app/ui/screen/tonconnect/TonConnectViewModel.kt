package network.tos.wallet.app.ui.screen.tonconnect

import android.app.Application
import androidx.lifecycle.viewModelScope
import network.tos.blockchain.ton.connect.TONProof
import network.tos.wallet.app.Environment
import network.tos.wallet.app.ui.base.BaseWalletVM
import network.tos.wallet.app.usecase.sign.SignUseCase
import network.tos.wallet.data.account.AccountRepository
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.dapps.entities.AppEntity
import network.tos.wallet.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

class TonConnectViewModel(
    app: Application,
    private val accountRepository: AccountRepository,
    private val signUseCase: SignUseCase,
    private val settingsRepository: SettingsRepository,
    private val environment: Environment,
): BaseWalletVM(app) {

    private val _stateFlow = MutableStateFlow<TonConnectScreenState?>(null)
    val stateFlow = _stateFlow.asStateFlow().filterNotNull()

    val installId: String
        get() = settingsRepository.installId

    val pushAvailable: Boolean
        get() = environment.isGooglePlayServicesAvailable

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val wallet = accountRepository.selectedWalletFlow.firstOrNull() ?: run {
                _stateFlow.value = TonConnectScreenState.Failure
                return@launch
            }

            val wallets = accountRepository.getWallets().filter { it.isTonConnectSupported }

            if (wallets.isEmpty()) {
                _stateFlow.value = TonConnectScreenState.Failure
                return@launch
            }

            val walletsCount = wallets.size

            _stateFlow.value = TonConnectScreenState.Data(
                wallet = if (wallet.isTonConnectSupported) wallet else wallets.first(),
                hasWalletPicker = walletsCount > 1
            )
        }
    }

    suspend fun requestProof(
        wallet: WalletEntity,
        app: AppEntity,
        proofPayload: String
    ) = signUseCase(context, wallet, app.url.host!!, proofPayload)

    fun setWallet(wallet: WalletEntity) {
        val state = _stateFlow.value as? TonConnectScreenState.Data ?: return
        _stateFlow.value = state.copy(wallet = wallet)
    }
}