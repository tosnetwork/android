package network.tos.wallet.app.ui.base.picker.currency.list

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import network.tos.uikit.list.BaseListAdapter
import network.tos.uikit.list.BaseListHolder
import network.tos.uikit.list.BaseListItem

class Adapter(val onClick: ((Item) -> Unit)): BaseListAdapter() {

    override fun createHolder(parent: ViewGroup, viewType: Int): BaseListHolder<out BaseListItem> {
        return Holder(parent, onClick)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        recyclerView.isNestedScrollingEnabled = true
    }
}