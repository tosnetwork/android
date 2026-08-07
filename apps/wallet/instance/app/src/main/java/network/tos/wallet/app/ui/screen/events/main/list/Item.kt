package network.tos.wallet.app.ui.screen.events.main.list

import android.net.Uri
import network.tos.extensions.uri
import network.tos.wallet.app.R
import network.tos.uikit.color.UIKitColor
import network.tos.uikit.icon.UIKitIcon
import network.tos.uikit.list.BaseListItem
import network.tos.uikit.list.ListCell
import network.tos.wallet.api.entity.AccountEntity
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.collectibles.entities.NftEntity
import network.tos.wallet.localization.Localization

sealed class Item(type: Int): BaseListItem(type) {

    companion object {
        const val TYPE_DATE = 0
        const val TYPE_ACTION = 1
        const val TYPE_SPACE = 2
    }

    data class Date(val date: String): Item(TYPE_DATE)

    open class Action(
        val position: ListCell.Position,
        val iconUri: Uri,
        val titleRes: Int,
        val subtitle: String,
        val comment: String? = null,
        val loading: Boolean = false,
        val value: CharSequence? = null,
        val value2: CharSequence? = null,
        val valueColorRef: Int = UIKitColor.textPrimaryColor,
        val nft: NftEntity? = null,
        val wallet: WalletEntity,
    ): Item(TYPE_ACTION)

    class SendAction(
        position: ListCell.Position,
        account: AccountEntity,
        comment: String?,
        loading: Boolean,
        value: CharSequence,
        nft: NftEntity? = null,
        wallet: WalletEntity
    ): Action(
        position = position,
        iconUri = account.iconUri ?: UIKitIcon.ic_tray_arrow_up_28.uri(),
        titleRes = Localization.sent,
        subtitle = account.accountName,
        comment = comment,
        loading = loading,
        value = value,
        nft = nft,
        wallet = wallet
    )

    class ReceiveAction(
        position: ListCell.Position,
        account: AccountEntity,
        comment: String?,
        loading: Boolean,
        value: CharSequence,
        nft: NftEntity? = null,
        wallet: WalletEntity
    ): Action(
        position = position,
        iconUri = account.iconUri ?: R.drawable.ic_tray_arrow_down_28.uri(),
        titleRes = Localization.received,
        subtitle = account.accountName,
        comment = comment,
        loading = loading,
        value = value,
        valueColorRef = UIKitColor.accentGreenColor,
        nft = nft,
        wallet = wallet
    )

    class Swap(
        position: ListCell.Position,
        loading: Boolean,
        accountName: String,
        value: String,
        value2: String,
        wallet: WalletEntity
    ): Action(
        position = position,
        iconUri = R.drawable.ic_swap_horizontal_alternative_28.uri(),
        titleRes = Localization.swap,
        subtitle = accountName,
        loading = loading,
        value = value,
        valueColorRef = UIKitColor.accentGreenColor,
        value2 = value2,
        wallet = wallet
    )

    class UnknownAction(
        position: ListCell.Position,
        wallet: WalletEntity
    ): Action(
        position = position,
        iconUri = UIKitIcon.ic_gear_28.uri(),
        titleRes = Localization.unknown,
        subtitle = "",
        comment = null,
        wallet = wallet
    )

    data object Space: Item(TYPE_SPACE)
}