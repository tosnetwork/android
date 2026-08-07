package network.tos.wallet.app.ui.screen.token.picker.list

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import network.tos.wallet.app.ui.screen.token.picker.list.holder.TokenHolder
import network.tos.uikit.list.BaseListAdapter
import network.tos.uikit.list.BaseListHolder
import network.tos.uikit.list.BaseListItem

class Adapter(
    private val onClick: (Item.Token) -> Unit
): BaseListAdapter() {

    override fun createHolder(parent: ViewGroup, viewType: Int): BaseListHolder<out BaseListItem> {
        return TokenHolder(parent, onClick)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        recyclerView.isNestedScrollingEnabled = true
    }
}