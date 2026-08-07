package network.tos.wallet.app.ui.screen.settings.extensions.list

import network.tos.uikit.list.BaseListItem
import network.tos.uikit.list.ListCell
import network.tos.wallet.data.account.entities.WalletEntity
import io.tonapi.models.WalletPlugin

sealed class Item(type: Int) : BaseListItem(type) {

    companion object {
        const val TYPE_PLUGIN = 0
    }

    data class Plugin(
        val plugin: WalletPlugin,
        val wallet: WalletEntity,
        val position: ListCell.Position
    ) : Item(TYPE_PLUGIN)
}



