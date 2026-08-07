package network.tos.wallet.app.ui.screen.swap.picker.list

import android.net.Uri
import network.tos.uikit.list.BaseListItem
import network.tos.uikit.list.ListCell
import network.tos.wallet.data.core.currency.WalletCurrency

sealed class Item(type: Int): BaseListItem(type) {

    companion object {
        const val TYPE_TOKEN = 0
        const val TYPE_TITLE = 1
    }

    data class Title(
        val text: String
    ) : Item(TYPE_TITLE)

    data class Token(
        val position: ListCell.Position = ListCell.Position.MIDDLE,
        val currency: WalletCurrency,
        val selected: Boolean,
        val fiatFormatted: CharSequence?
    ): Item(TYPE_TOKEN) {

        val iconUri: Uri
            get() = currency.iconUri ?: Uri.EMPTY

        val code: String
            get() = currency.code

        val name: CharSequence
            get() = fiatFormatted ?: currency.title
    }
}