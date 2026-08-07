package network.tos.wallet.app.ui.screen.qr

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import network.tos.wallet.app.core.entities.AssetsEntity
import network.tos.wallet.app.core.entities.AssetsExtendedEntity
import network.tos.wallet.app.extensions.isSafeModeEnabled
import network.tos.wallet.app.ui.base.BaseWalletVM
import network.tos.wallet.api.API
import network.tos.wallet.api.entity.TokenEntity
import network.tos.wallet.data.account.AccountRepository
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.settings.SettingsRepository
import network.tos.wallet.data.token.TokenRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class QRViewModel(
    app: Application,
    private val wallet: WalletEntity,
    private val initialToken: TokenEntity,
    private val settingsRepository: SettingsRepository,
    private val accountRepository: AccountRepository,
    private val tokenRepository: TokenRepository,
    private val api: API,
) : BaseWalletVM(app) {

    enum class Tab {
        TON, TRON
    }

    private val safeMode: Boolean = settingsRepository.isSafeModeEnabled(api)

    val installId: String
        get() = settingsRepository.installId

    val tronUsdtEnabled: Boolean
        get() = settingsRepository.getTronUsdtEnabled(wallet.id)

    val isTronDisabled: Boolean
        get() = api.config.flags.disableTron

    var token: TokenEntity by mutableStateOf(initialToken)
        private set

    var address: String by mutableStateOf("")
        private set

    private lateinit var tronAddress: String

    private val tokensFlow = settingsRepository.tokenPrefsChangedFlow.map { _ ->
        tokenRepository.mustGet(settingsRepository.currency, wallet.accountId, wallet.testnet)
            .mapNotNull { token ->
                if (safeMode && !token.verified) {
                    return@mapNotNull null
                }
                AssetsExtendedEntity(
                    raw = AssetsEntity.Token(token),
                    prefs = settingsRepository.getTokenPrefs(
                        wallet.id,
                        token.address,
                        token.blacklist
                    ),
                    accountId = wallet.accountId,
                )
            }.filter { !it.isTon }.sortedBy { it.index }
    }

    val hasTronBalanceFlow = tokensFlow.map { tokens ->
        tokens.any { it.address == TokenEntity.TRON_USDT.address && it.balance.value.isPositive }
    }

    init {
        viewModelScope.launch {
            tronAddress = accountRepository.getTronAddress(wallet.id) ?: ""
            address = if (token.isTrc20) {
                tronAddress
            } else {
                wallet.address
            }
        }
    }

    suspend fun enableTron() {
        val tokens = tokensFlow.first()
        val usdtIndex = tokens.indexOfFirst { it.address == TokenEntity.USDT.address }
        val sortAddresses = mutableListOf<String>()
        tokens.forEachIndexed { index, token ->
            sortAddresses.add(token.address)
            if (index == usdtIndex + 1 && token.address != TokenEntity.TRON_USDT.address) {
                sortAddresses.add(TokenEntity.TRON_USDT.address)
            }
        }
        settingsRepository.setTokenHidden(wallet.id, TokenEntity.TRON_USDT.address, false)
        settingsRepository.setTokenPinned(wallet.id, TokenEntity.TRON_USDT.address, true)
        settingsRepository.setTokensSort(wallet.id, sortAddresses)
        setTron()
    }

    fun setTron() {
        token = TokenEntity.TRON_USDT
        address = tronAddress
    }

    fun setTon() {
        token = TokenEntity.TON
        address = wallet.address
    }

}