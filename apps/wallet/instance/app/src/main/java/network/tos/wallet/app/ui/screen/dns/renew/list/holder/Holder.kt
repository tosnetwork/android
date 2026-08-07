package network.tos.wallet.app.ui.screen.dns.renew.list.holder

import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatTextView
import network.tos.extensions.max12
import network.tos.extensions.short12
import network.tos.wallet.app.ui.screen.dns.renew.list.Item
import network.tos.wallet.app.ui.screen.nft.NftScreen
import network.tos.wallet.app.R
import network.tos.uikit.color.UIKitColor
import network.tos.uikit.color.resolveColor
import network.tos.uikit.list.BaseListHolder
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.collectibles.entities.NftEntity
import network.tos.wallet.localization.Localization
import uikit.extensions.drawable
import uikit.extensions.withDefaultBadge
import uikit.navigation.Navigation

class Holder(parent: ViewGroup): BaseListHolder<Item>(parent, R.layout.view_domain_renew) {

    private val navigation = Navigation.from(context)
    private val nameView = findViewById<AppCompatTextView>(R.id.name)
    private val detailsView = findViewById<AppCompatTextView>(R.id.details)

    override fun onBind(item: Item) {
        itemView.background = item.position.drawable(context)
        itemView.setOnClickListener {
            item.nft?.let { openNft(item.wallet, it) }
        }
        nameView.text = if (item.inSale) {
            item.name.max12.withDefaultBadge(context, Localization.on_sale)
        } else {
            item.name
        }

        detailsView.text = context.getString(Localization.renew_dns_expires, item.daysUntilExpiration)
        if (7 >= item.daysUntilExpiration) {
            detailsView.setTextColor(context.resolveColor(UIKitColor.accentRedColor))
        } else {
            detailsView.setTextColor(context.resolveColor(UIKitColor.textSecondaryColor))
        }
    }

    private fun openNft(wallet: WalletEntity, nft: NftEntity) {
        navigation?.add(NftScreen.newInstance(wallet, nft))
    }

}