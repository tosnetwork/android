package network.tos.wallet.app.ui.screen.init.list

import android.os.Parcelable
import network.tos.blockchain.ton.contract.WalletVersion
import network.tos.uikit.list.BaseListItem
import network.tos.uikit.list.ListCell
import kotlinx.parcelize.Parcelize

@Parcelize
data class AccountItem(
    val address: String,
    val name: String?,
    val walletVersion: WalletVersion,
    val balanceFormat: CharSequence,
    val tokens: Boolean,
    val collectibles: Boolean,
    val selected: Boolean,
    val position: ListCell.Position,
    val ledgerIndex: Int? = null,
    val ledgerAdded: Boolean = false,
    val initialized: Boolean,
): BaseListItem(0), Parcelable