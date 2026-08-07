package network.tos.wallet.app.ui.screen.settings.language.list

import network.tos.uikit.list.BaseListItem
import network.tos.uikit.list.ListCell

data class Item(
    val name: String,
    val nameLocalized: String = "",
    val selected: Boolean = false,
    val code: String,
    val position: ListCell.Position
): BaseListItem()