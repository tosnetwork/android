package network.tos.wallet.app.ui.screen.events.main.list

import android.view.ViewGroup
import network.tos.wallet.app.ui.screen.events.main.list.holder.ActionHolder
import network.tos.wallet.app.ui.screen.events.main.list.holder.DateHolder
import network.tos.wallet.app.ui.screen.events.main.list.holder.SpaceHolder
import network.tos.uikit.list.BaseListAdapter
import network.tos.uikit.list.BaseListHolder
import network.tos.uikit.list.BaseListItem

class Adapter: BaseListAdapter() {

    override fun createHolder(parent: ViewGroup, viewType: Int): BaseListHolder<out BaseListItem> {
        return when(viewType) {
            Item.TYPE_DATE -> DateHolder(parent)
            Item.TYPE_ACTION -> ActionHolder(parent)
            Item.TYPE_SPACE -> SpaceHolder(parent)
            else -> throw IllegalArgumentException("Unknown viewType: $viewType")
        }
    }

}