package network.tos.wallet.app.ui.screen.wallet.manage.list

import android.net.Uri
import androidx.annotation.StringRes
import network.tos.icu.Coins
import network.tos.icu.CurrencyFormatter
import network.tos.wallet.app.core.entities.AssetsExtendedEntity
import network.tos.uikit.list.BaseListItem
import network.tos.uikit.list.ListCell
import network.tos.wallet.api.entity.value.Blockchain
import network.tos.wallet.api.entity.TokenEntity
import network.tos.wallet.data.account.entities.WalletEntity

sealed class Item(type: Int): BaseListItem(type) {

    companion object {
        const val TYPE_TITLE = 1
        const val TYPE_TOKEN = 2
        const val TYPE_SPACE = 3
        const val TYPE_SAFE_MODE = 4
    }

    data class Title(
        @StringRes val titleRes: Int,
        @StringRes val detailsRes: Int = 0
    ): Item(TYPE_TITLE)

    data class Token(
        val position: ListCell.Position,
        val iconUri: Uri,
        val address: String,
        val symbol: String,
        val balance: Coins,
        val balanceFormat: CharSequence,
        val pinned: Boolean,
        val hidden: Boolean,
        val hiddenBalance: Boolean,
        val verified: Boolean,
        val blacklist: Boolean,
        val blockchain: Blockchain,
    ): Item(TYPE_TOKEN) {

        val isUSDT: Boolean
            get() = address == TokenEntity.USDT.address
        val isTRC20: Boolean
            get() = address == TokenEntity.TRON_USDT.address

        constructor(
            position: ListCell.Position,
            token: AssetsExtendedEntity,
            hiddenBalance: Boolean,
        ) : this(
            position = position,
            iconUri = token.imageUri,
            address = token.address,
            symbol = token.symbol,
            balance = token.balance.value,
            balanceFormat = CurrencyFormatter.format(token.symbol, token.balance.value),
            pinned = token.pinned,
            hidden = token.hidden,
            hiddenBalance = hiddenBalance,
            verified = token.verified,
            blacklist = token.blacklist,
            blockchain = token.balance.token.blockchain,
        )
    }

    data object Space: Item(TYPE_SPACE)

    data class SafeMode(val wallet: WalletEntity): Item(TYPE_SAFE_MODE)
}