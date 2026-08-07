package network.tos.wallet.app.ui.screen.ledger.steps.list

import android.view.ViewGroup
import network.tos.wallet.app.ui.screen.ledger.steps.list.holder.StepHolder
import network.tos.uikit.list.BaseListAdapter
import network.tos.uikit.list.BaseListHolder
import network.tos.uikit.list.BaseListItem

class Adapter(
    private val onInstallTonAppClick: () -> Unit
) : BaseListAdapter() {
    override fun createHolder(parent: ViewGroup, viewType: Int): BaseListHolder<out BaseListItem> {
        return when (viewType) {
            Item.TYPE_STEP -> StepHolder(parent, onInstallTonAppClick)
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

}