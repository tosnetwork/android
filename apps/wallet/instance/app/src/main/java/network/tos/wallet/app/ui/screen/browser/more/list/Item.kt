package network.tos.wallet.app.ui.screen.browser.more.list

import android.net.Uri
import network.tos.uikit.list.BaseListItem
import network.tos.uikit.list.ListCell
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.browser.entities.BrowserAppEntity

data class Item(
    val wallet: WalletEntity,
    val app: BrowserAppEntity,
    val position: ListCell.Position,
    val country: String
): BaseListItem(0) {

    val icon: Uri
        get() = app.icon

    val name: String
        get() = app.name

    val url: Uri
        get() = app.url

    val description: String
        get() = app.description
}