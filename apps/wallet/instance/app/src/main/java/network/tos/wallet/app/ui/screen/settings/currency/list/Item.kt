package network.tos.wallet.app.ui.screen.settings.currency.list

import network.tos.uikit.list.BaseListItem
import network.tos.uikit.list.ListCell

data class Item(
    val currency: String,
    val name: String,
    val selected: Boolean,
    val position: ListCell.Position
): BaseListItem()