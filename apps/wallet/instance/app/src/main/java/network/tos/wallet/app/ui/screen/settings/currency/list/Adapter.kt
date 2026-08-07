package network.tos.wallet.app.ui.screen.settings.currency.list

import android.view.ViewGroup
import network.tos.uikit.list.BaseListAdapter
import network.tos.uikit.list.BaseListHolder
import network.tos.uikit.list.BaseListItem

class Adapter(
    private val onClick: (currency: String) -> Unit
): BaseListAdapter() {

    override fun createHolder(parent: ViewGroup, viewType: Int): BaseListHolder<out BaseListItem> {
        return Holder(parent, onClick)
    }
}