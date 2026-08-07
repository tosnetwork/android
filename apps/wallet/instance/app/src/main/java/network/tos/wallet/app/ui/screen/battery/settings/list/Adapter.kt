package network.tos.wallet.app.ui.screen.battery.settings.list

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import network.tos.wallet.app.ui.screen.battery.settings.list.holder.SettingsHeaderHolder
import network.tos.wallet.app.ui.screen.battery.settings.list.holder.SupportedTransactionHolder
import network.tos.uikit.list.BaseListAdapter
import network.tos.uikit.list.BaseListHolder
import network.tos.uikit.list.BaseListItem

class Adapter: BaseListAdapter() {

    override fun createHolder(parent: ViewGroup, viewType: Int): BaseListHolder<out BaseListItem> {
        return when(viewType) {
            Item.TYPE_SETTINGS_HEADER -> SettingsHeaderHolder(parent)
            Item.TYPE_SUPPORTED_TRANSACTION -> SupportedTransactionHolder(parent)
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        recyclerView.isNestedScrollingEnabled = true
    }
}