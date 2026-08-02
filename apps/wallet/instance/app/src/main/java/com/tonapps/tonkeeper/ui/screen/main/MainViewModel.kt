package com.tonapps.tonkeeper.ui.screen.main

import android.app.Application
import com.tonapps.extensions.MutableEffectFlow
import com.tonapps.tonkeeper.ui.base.BaseWalletVM
import com.tonapps.wallet.api.API
import com.tonapps.wallet.data.account.AccountRepository
import com.tonapps.wallet.data.account.entities.WalletEntity
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
