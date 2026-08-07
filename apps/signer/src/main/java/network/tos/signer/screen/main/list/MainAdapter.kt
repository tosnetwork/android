package network.tos.signer.screen.main.list

import android.view.ViewGroup
import network.tos.signer.screen.main.list.holder.MainAccountHolder
import network.tos.signer.screen.main.list.holder.MainActionsHolder

class MainAdapter(
    private val selectAccountCallback: (id: Long) -> Unit
): network.tos.uikit.list.BaseListAdapter() {

    override fun createHolder(parent: ViewGroup, viewType: Int): network.tos.uikit.list.BaseListHolder<out network.tos.uikit.list.BaseListItem> {
        return when (viewType) {
            MainItem.TYPE_ACTIONS -> MainActionsHolder(parent)
            MainItem.TYPE_ACCOUNT -> MainAccountHolder(parent, selectAccountCallback)
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun getItemId(position: Int): Long {
        val item = getItem(position) as MainItem
        return item.id
    }
}