package network.tos.wallet.app.ui.screen.settings.apps.list

import android.view.ViewGroup
import network.tos.wallet.app.ui.screen.settings.apps.list.holder.AppHolder
import network.tos.wallet.app.ui.screen.settings.apps.list.holder.DisconnectAllHolder
import network.tos.wallet.app.ui.screen.settings.apps.list.holder.EmptyHolder
import network.tos.uikit.list.BaseListAdapter
import network.tos.uikit.list.BaseListHolder
import network.tos.uikit.list.BaseListItem
import network.tos.wallet.data.dapps.entities.AppEntity

class Adapter(
    private val disconnectApp: (app: AppEntity) -> Unit,
    private val disconnectAll: () -> Unit
): BaseListAdapter() {
    override fun createHolder(parent: ViewGroup, viewType: Int): BaseListHolder<out BaseListItem> {
        return when (viewType) {
            Item.TYPE_APP -> AppHolder(parent, disconnectApp)
            Item.TYPE_DISCONNECT_ALL -> DisconnectAllHolder(parent, disconnectAll)
            Item.TYPE_EMPTY -> EmptyHolder(parent)
            else -> throw IllegalArgumentException("Unknown viewType: $viewType")
        }
    }
}