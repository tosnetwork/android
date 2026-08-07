package network.tos.wallet.app.ui.screen.settings.extensions.list

import android.view.ViewGroup
import network.tos.wallet.app.ui.screen.settings.extensions.list.holder.PluginHolder
import network.tos.uikit.list.BaseListAdapter
import network.tos.uikit.list.BaseListHolder
import network.tos.uikit.list.BaseListItem

class Adapter(): BaseListAdapter() {
    override fun createHolder(parent: ViewGroup, viewType: Int): BaseListHolder<out BaseListItem> {
        return when (viewType) {
            Item.TYPE_PLUGIN -> PluginHolder(parent)
            else -> throw IllegalArgumentException("Unknown viewType: $viewType")
        }
    }
}



