package network.tos.wallet.app.ui.screen.events.main.filters

import android.view.ViewGroup
import network.tos.wallet.app.ui.screen.events.main.filters.holder.AppHolder
import network.tos.wallet.app.ui.screen.events.main.filters.holder.FilterHolder
import network.tos.uikit.list.BaseListAdapter
import network.tos.uikit.list.BaseListHolder
import network.tos.uikit.list.BaseListItem

class FiltersAdapter(private val onClick: (item: FilterItem) -> Unit): BaseListAdapter() {

    override fun createHolder(parent: ViewGroup, viewType: Int): BaseListHolder<out BaseListItem> {
        return when(viewType) {
            FilterItem.TYPE_ALL, FilterItem.TYPE_SEND, FilterItem.TYPE_RECEIVE, FilterItem.TYPE_SPAM, FilterItem.TYPE_DAPPS -> FilterHolder(parent, onClick)
            FilterItem.TYPE_APP -> AppHolder(parent, onClick)
            else -> throw IllegalArgumentException("Unknown viewType: $viewType")
        }
    }

}