package network.tos.wallet.app.extensions

import network.tos.extensions.uri
import network.tos.wallet.app.popup.ActionSheet
import network.tos.wallet.app.popup.ActionSheet.Item
import network.tos.wallet.app.ui.screen.send.main.state.SendFee
import network.tos.uikit.color.accentGreenColor
import network.tos.uikit.icon.UIKitIcon
import network.tos.wallet.localization.Localization
import uikit.extensions.drawable

fun ActionSheet.addFeeItem(
    fee: SendFee,
    selected: Boolean,
    onClick: ((Item) -> Unit)?
) {
    val id = fee.id.hashCode().toLong()
    val icon = if (selected) context.drawable(UIKitIcon.ic_done_16) else null
    if (fee is SendFee.TokenFee) {
        addItem(
            id = id,
            title = fee.symbol,
            subtitle = "≈ ${fee.formattedAmount} · ${fee.formattedFiat}",
            imageUri = fee.amount.token.imageUri,
            icon = icon,
            onClick = onClick
        )
    } else if (fee is SendFee.Battery) {
        addItem(
            id = id,
            title = context.getString(Localization.battery_refill_title),
            subtitle = "≈ ${fee.formattedCharges(context)}",
            imageUri = UIKitIcon.ic_flash_24.uri(),
            imageTintColor = context.accentGreenColor,
            icon = icon,
            onClick = onClick
        )
    }
}