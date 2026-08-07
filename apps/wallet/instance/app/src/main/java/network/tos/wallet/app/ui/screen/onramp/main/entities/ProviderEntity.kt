package network.tos.wallet.app.ui.screen.onramp.main.entities

import android.os.Parcelable
import network.tos.icu.Coins
import network.tos.wallet.api.entity.OnRampMerchantEntity
import network.tos.wallet.data.purchase.entity.MerchantEntity
import network.tos.wallet.data.purchase.entity.PurchaseMethodEntity
import kotlinx.parcelize.Parcelize

@Parcelize
data class ProviderEntity(
    val id: String,
    val receive: Double,
    val title: String,
    val iconUrl: String,
    val widgetUrl: String,
    val buttons: List<MerchantEntity.Button>,
    val description: String? = null,
    val minAmount: Double
): Parcelable {

    constructor(
        widget: OnRampMerchantEntity,
        details: MerchantEntity
    ) : this(
        id = details.id,
        receive = widget.amount,
        title = details.title,
        iconUrl = details.image,
        widgetUrl = widget.widgetUrl,
        buttons = details.buttons,
        description = details.description,
        minAmount = widget.minAmount
    )
}