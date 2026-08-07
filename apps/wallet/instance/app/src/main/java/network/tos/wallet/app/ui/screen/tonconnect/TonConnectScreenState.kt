package network.tos.wallet.app.ui.screen.tonconnect

import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.dapps.entities.AppEntity

sealed class TonConnectScreenState {

    data class Data(
        val wallet: WalletEntity,
        val hasWalletPicker: Boolean
    ): TonConnectScreenState()

    data object Failure: TonConnectScreenState()
}