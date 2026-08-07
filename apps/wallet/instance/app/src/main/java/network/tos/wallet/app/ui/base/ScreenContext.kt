package network.tos.wallet.app.ui.base

import android.os.Parcelable
import network.tos.wallet.data.account.entities.WalletEntity
import kotlinx.parcelize.Parcelize

sealed class ScreenContext: Parcelable {

    @Parcelize
    data object None : ScreenContext()

    @Parcelize
    data object Ignore : ScreenContext()

    @Parcelize
    data class Wallet(val wallet: WalletEntity) : ScreenContext() {

        val isEmpty: Boolean
            get() = wallet.id.isBlank()
    }
}