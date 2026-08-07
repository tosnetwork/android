package network.tos.wallet.app.ui.base.compose

import network.tos.wallet.app.ui.base.ScreenContext
import network.tos.wallet.data.account.entities.WalletEntity

abstract class ComposeWalletScreen(wallet: WalletEntity): ComposeScreen<ScreenContext.Wallet>(ScreenContext.Wallet(wallet)) {

    val wallet: WalletEntity
        get() = (screenContext as ScreenContext.Wallet).wallet
}