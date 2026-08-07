package network.tos.wallet.app.ui.screen.ledger.steps.list

import network.tos.uikit.list.BaseListItem
import network.tos.uikit.list.ListCell

sealed class Item(type: Int): BaseListItem(type) {

    companion object {
        const val TYPE_STEP = 0
    }

    data class Step(
        val label: String,
        val isDone: Boolean,
        val isCurrent: Boolean,
        val showInstallTon: Boolean = false
    ): Item(TYPE_STEP)

}