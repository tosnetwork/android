package network.tos.wallet.app.ui.screen.add.list

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import network.tos.wallet.app.ui.screen.add.list.holder.HeaderHolder
import network.tos.wallet.app.ui.screen.add.list.holder.WalletHolder
import network.tos.uikit.list.BaseListAdapter
import network.tos.uikit.list.BaseListHolder
import network.tos.uikit.list.BaseListItem

class Adapter(
    private val onClick: (Item.Wallet) -> Unit
): BaseListAdapter() {

    override fun createHolder(parent: ViewGroup, viewType: Int): BaseListHolder<out BaseListItem> {
        return when(viewType) {
            Item.TYPE_HEADER -> HeaderHolder(parent)
            Item.TYPE_WALLET -> WalletHolder(parent, onClick)
            else -> throw IllegalArgumentException("Unknown viewType: $viewType")
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        recyclerView.isNestedScrollingEnabled = true
        recyclerView.setHasFixedSize(false)
    }
}
