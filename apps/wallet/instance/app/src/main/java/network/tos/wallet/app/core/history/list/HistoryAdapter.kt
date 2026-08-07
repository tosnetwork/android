package network.tos.wallet.app.core.history.list

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import network.tos.wallet.app.core.history.list.holder.HistoryActionHolder
import network.tos.wallet.app.core.history.list.holder.HistoryAppHolder
import network.tos.wallet.app.core.history.list.holder.HistoryEmptyHolder
import network.tos.wallet.app.core.history.list.holder.HistoryFailedHolder
import network.tos.wallet.app.core.history.list.holder.HistoryHeaderHolder
import network.tos.wallet.app.core.history.list.holder.HistoryLoaderHolder
import network.tos.wallet.app.core.history.list.item.HistoryItem
import network.tos.wallet.app.ui.screen.send.main.state.SendFee
import network.tos.uikit.list.BaseListAdapter
import network.tos.uikit.list.BaseListHolder
import network.tos.uikit.list.BaseListItem

open class HistoryAdapter(
    private val disableOpenAction: Boolean = false,
    private val shouldShowFeeToggle: () -> Boolean = { true },
    private val showFeeMethods: (currentFee: SendFee, targetView: View) -> Unit = { _, _ -> }
) : BaseListAdapter() {

    init {
        // super.setHasStableIds(true)
    }

    fun setEmpty() {
        submitList(emptyList())
    }

    override fun getItemId(position: Int): Long {
        val item = super.getItem(position) as? HistoryItem ?: return RecyclerView.NO_ID
        return item.timestampForSort
    }

    override fun createHolder(
        parent: ViewGroup,
        viewType: Int
    ): BaseListHolder<out BaseListItem> {
        return when (viewType) {
            HistoryItem.TYPE_ACTION -> HistoryActionHolder(
                parent,
                disableOpenAction,
                shouldShowFeeToggle,
                showFeeMethods
            )

            HistoryItem.TYPE_HEADER -> HistoryHeaderHolder(parent)
            HistoryItem.TYPE_LOADER -> HistoryLoaderHolder(parent)
            HistoryItem.TYPE_APP -> HistoryAppHolder(parent)
            HistoryItem.TYPE_EMPTY -> HistoryEmptyHolder(parent)
            HistoryItem.TYPE_FAILED -> HistoryFailedHolder(parent)
            else -> throw IllegalArgumentException("Unknown viewType: $viewType")
        }
    }
}