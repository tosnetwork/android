package network.tos.wallet.app.ui.screen.dns.renew.list

import network.tos.uikit.list.BaseListItem
import network.tos.uikit.list.ListCell
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.collectibles.entities.DnsExpiringEntity
import network.tos.wallet.data.collectibles.entities.NftEntity

data class Item(
    val position: ListCell.Position,
    val wallet: WalletEntity,
    val entity: DnsExpiringEntity,
): BaseListItem() {

    val name: String
        get() = entity.name

    val nft: NftEntity?
        get() = entity.dnsItem

    val inSale: Boolean
        get() = entity.inSale

    val daysUntilExpiration: Int by lazy {
        val currentTime = System.currentTimeMillis() / 1000
        val remainingSeconds = entity.expiringAt - currentTime

        if (remainingSeconds <= 0) {
            0
        } else {
            (remainingSeconds / (24 * 60 * 60)).toInt()
        }
    }

}