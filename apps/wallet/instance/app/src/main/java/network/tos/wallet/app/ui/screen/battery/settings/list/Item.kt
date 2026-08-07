package network.tos.wallet.app.ui.screen.battery.settings.list

import network.tos.uikit.list.BaseListItem
import network.tos.uikit.list.ListCell
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.settings.BatteryTransaction
import network.tos.wallet.localization.Localization

sealed class Item(type: Int) : BaseListItem(type) {

    companion object {
        const val TYPE_SETTINGS_HEADER = 1
        const val TYPE_SUPPORTED_TRANSACTION = 2
    }

    data class SupportedTransaction(
        val wallet: WalletEntity,
        val position: ListCell.Position,
        val supportedTransaction: BatteryTransaction,
        val enabled: Boolean,
        val changes: Int,
        val changesRange: Pair<Int, Int>? = null,
        val showToggle: Boolean
    ) : Item(TYPE_SUPPORTED_TRANSACTION) {

        val accountId: String
            get() = wallet.accountId

        val titleRes: Int
            get() = when(supportedTransaction) {
                BatteryTransaction.NFT -> Localization.battery_nft
                BatteryTransaction.SWAP -> Localization.battery_swap
                BatteryTransaction.JETTON -> Localization.battery_jetton
                BatteryTransaction.TRC20 -> Localization.battery_trc20
                else -> throw IllegalArgumentException("Unsupported transaction type: $supportedTransaction")
            }

        val typeTitleRes: Int
            get() = when(supportedTransaction) {
                BatteryTransaction.NFT, BatteryTransaction.JETTON, BatteryTransaction.TRC20 -> Localization.battery_transfer_single
                BatteryTransaction.SWAP -> Localization.battery_swap_single
                else -> throw IllegalArgumentException("Unsupported transaction type: $supportedTransaction")
            }
    }

    data object SettingsHeader: Item(TYPE_SETTINGS_HEADER)
}