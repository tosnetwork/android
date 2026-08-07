package network.tos.wallet.app.ui.screen.notifications.list

import android.net.Uri
import android.view.ViewGroup
import network.tos.wallet.app.ui.screen.notifications.list.holder.AppHolder
import network.tos.wallet.app.ui.screen.notifications.list.holder.AppsHeaderHolder
import network.tos.wallet.app.ui.screen.notifications.list.holder.SpaceHolder
import network.tos.wallet.app.ui.screen.notifications.list.holder.WalletPushHolder
import network.tos.uikit.list.BaseListAdapter
import network.tos.uikit.list.BaseListHolder
import network.tos.uikit.list.BaseListItem
import network.tos.wallet.data.account.entities.WalletEntity

class Adapter(
    private val onWalletCallback: (WalletEntity, Boolean) -> Unit,
    private val onDAppCallback: (Uri, Boolean) -> Unit
): BaseListAdapter() {
    override fun createHolder(parent: ViewGroup, viewType: Int): BaseListHolder<out BaseListItem> {
        return when(viewType) {
            Item.TYPE_WALLET -> WalletPushHolder(parent, onWalletCallback)
            Item.TYPE_SPACE -> SpaceHolder(parent)
            Item.TYPE_APPS_HEADER -> AppsHeaderHolder(parent)
            Item.TYPE_APP -> AppHolder(parent, onDAppCallback)
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

}