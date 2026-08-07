package network.tos.wallet.app.ui.screen.token.picker.list

import android.net.Uri
import network.tos.uikit.list.BaseListItem
import network.tos.uikit.list.ListCell
import network.tos.wallet.api.entity.value.Blockchain
import network.tos.wallet.data.token.entities.AccountTokenEntity

sealed class Item(type: Int): BaseListItem(type) {

    companion object {
        const val TYPE_TOKEN = 0
        const val TYPE_SKELETON = 1
    }

    data class Skeleton(
        val position: ListCell.Position,
    ): Item(TYPE_SKELETON)

    data class Token(
        val position: ListCell.Position,
        val raw: AccountTokenEntity,
        val selected: Boolean,
        val balance: CharSequence,
        val hiddenBalance: Boolean,
        val showNetwork: Boolean,
    ): Item(TYPE_TOKEN) {

        val blockchain: Blockchain
            get() = raw.token.blockchain

        val iconUri: Uri
            get() = raw.imageUri

        val symbol: String
            get() = raw.symbol

        val isTrc20: Boolean
            get() = raw.isTrc20

        val isUsdt: Boolean
            get() = raw.isUsdt
    }
}