package network.tos.wallet.app.ui.screen.browser.main.list.connected

import android.net.Uri
import network.tos.uikit.list.BaseListItem
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.dapps.entities.AppEntity

data class ConnectedItem(
    val wallet: WalletEntity,
    val app: AppEntity,
): BaseListItem(0) {

    val icon: Uri
        get() = Uri.parse(app.iconUrl)

    val name: String
        get() = app.name

    val url: Uri
        get() = app.url
}