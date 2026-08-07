package network.tos.wallet.app.ui.screen.dev.list.launcher

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import network.tos.wallet.app.core.LauncherIcon
import network.tos.wallet.app.extensions.showToast
import network.tos.uikit.list.BaseListAdapter
import network.tos.uikit.list.BaseListHolder
import network.tos.uikit.list.BaseListItem

class LauncherAdapter: BaseListAdapter() {

    init {
        submitList(LauncherIcon.entries.map { Item(it) })
    }

    override fun createHolder(parent: ViewGroup, viewType: Int): BaseListHolder<out BaseListItem> {
        return Holder(parent, ::enableIcon)
    }

    private fun enableIcon(view: View, position: Int) {
        val icon = LauncherIcon.entries[position]
        if (LauncherIcon.setEnable(view.context, icon)) {
            view.context.showToast("Icon changed")
            view.postOnAnimation {
                notifyDataSetChanged()
            }
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        recyclerView.isNestedScrollingEnabled = true
    }
}