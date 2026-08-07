package network.tos.wallet.app.ui.screen.main

import android.app.Application
import network.tos.extensions.MutableEffectFlow
import network.tos.wallet.app.ui.base.BaseWalletVM
import network.tos.wallet.api.API
import network.tos.wallet.data.account.AccountRepository
import network.tos.wallet.data.account.entities.WalletEntity
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map

class MainViewModel(
    app: Application,
    private val accountRepository: AccountRepository,
    private val api: API,
) : BaseWalletVM(app) {

    private var currentWallet: WalletEntity? = null

    private val _childBottomScrolled = MutableEffectFlow<Boolean>()
    val childBottomScrolled = _childBottomScrolled.asSharedFlow()

    val selectedWalletFlow = accountRepository.selectedWalletFlow

    val disbleNftsFlow = api.configFlow.map { it.flags.disableNfts }

    fun setBottomScrolled(value: Boolean) {
        _childBottomScrolled.tryEmit(value)
    }

    fun setData(wallet: WalletEntity, itemId: Int) {
        currentWallet = wallet
    }
}
