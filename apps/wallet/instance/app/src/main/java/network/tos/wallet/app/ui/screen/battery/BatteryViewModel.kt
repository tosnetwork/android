package network.tos.wallet.app.ui.screen.battery

import android.app.Application
import android.util.Log
import androidx.lifecycle.viewModelScope
import network.tos.blockchain.ton.extensions.equalsAddress
import network.tos.extensions.MutableEffectFlow
import network.tos.wallet.app.ui.base.BaseWalletVM
import network.tos.wallet.app.ui.screen.battery.recharge.BatteryRechargeScreen
import network.tos.wallet.app.ui.screen.settings.main.SettingsViewModel
import network.tos.wallet.api.API
import network.tos.wallet.data.account.AccountRepository
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.battery.BatteryMapper
import network.tos.wallet.data.battery.BatteryRepository
import network.tos.wallet.data.battery.entity.BatteryBalanceEntity
import network.tos.wallet.data.settings.SettingsRepository
import network.tos.wallet.data.token.TokenRepository
import network.tos.wallet.data.token.entities.AccountTokenEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class BatteryViewModel(
    app: Application,
    private val wallet: WalletEntity,
    jetton: String,
    private val settingsRepository: SettingsRepository,
    private val batteryRepository: BatteryRepository,
    private val tokenRepository: TokenRepository,
    private val accountRepository: AccountRepository,
) : BaseWalletVM(app) {

    private val _routeFlow = MutableEffectFlow<BatteryRoute>()
    val routeFlow = _routeFlow.asSharedFlow().filterNotNull()

    val installId: String
        get() = settingsRepository.installId

    init {
        routeToRefill()
        if (jetton.isNotEmpty()) {
            openRecharge(jetton)
        }
    }

    private fun openRecharge(jetton: String) {
        viewModelScope.launch {
            val rechargeToken =
                batteryRepository.getRechargeMethodByJetton(wallet.testnet, jetton)?.jettonMaster
                    ?: "TON"
            val tokens =
                tokenRepository.get(settingsRepository.currency, wallet.accountId, wallet.testnet)
                    ?: return@launch
            val token =
                tokens.firstOrNull { it.address.equalsAddress(rechargeToken) } ?: return@launch
            openScreen(BatteryRechargeScreen.newInstance(wallet, token))
        }
    }

    fun routeToSettings() {
        _routeFlow.tryEmit(BatteryRoute.Settings)
    }

    private fun routeToRefill() {
        _routeFlow.tryEmit(BatteryRoute.Refill)
    }

    fun setBatteryViewed() {
        if (!settingsRepository.batteryViewed) {
            viewModelScope.launch {
                settingsRepository.batteryViewed = true
                val tonProofToken =
                    accountRepository.requestTonProofToken(wallet) ?: return@launch
                batteryRepository.getBalance(
                    tonProofToken = tonProofToken,
                    publicKey = wallet.publicKey,
                    testnet = wallet.testnet,
                    ignoreCache = true
                )
            }
        }
    }
}