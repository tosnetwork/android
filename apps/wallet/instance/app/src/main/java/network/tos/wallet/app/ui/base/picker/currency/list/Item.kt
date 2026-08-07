package network.tos.wallet.app.ui.base.picker.currency.list

import android.net.Uri
import network.tos.extensions.toUriOrNull
import network.tos.wallet.app.os.AndroidCurrency
import network.tos.uikit.list.BaseListItem
import network.tos.uikit.list.ListCell
import network.tos.wallet.data.core.currency.WalletCurrency

data class Item(
    val position: ListCell.Position,
    val currency: WalletCurrency,
    val extra: String,
): BaseListItem() {

    val code: String
        get() = currency.code

    val name: String
        get() = extra.ifEmpty {
            currency.title
        }

    val drawableRes: Int?
        get() = currency.drawableRes

    val iconUri: Uri?
        get() = currency.iconUrl?.toUriOrNull()
}