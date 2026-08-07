package network.tos.wallet.app.ui.screen.settings.main.list.holder

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import network.tos.emoji.ui.EmojiView
import network.tos.wallet.app.extensions.getWalletBadges
import network.tos.wallet.app.ui.screen.settings.main.list.Item
import network.tos.wallet.app.R
import network.tos.uikit.color.iconTertiaryColor
import network.tos.uikit.color.stateList
import network.tos.uikit.icon.UIKitIcon
import network.tos.uikit.list.ListCell
import network.tos.wallet.data.account.Wallet
import network.tos.wallet.localization.Localization
import uikit.extensions.drawable

class AccountHolder(
    parent: ViewGroup,
    onClick: ((Item) -> Unit)
): Holder<Item.Account>(parent, R.layout.view_wallet_item, onClick) {

    private val colorView = findViewById<View>(R.id.wallet_color)
    private val emojiView = findViewById<EmojiView>(R.id.wallet_emoji)
    private val nameView = findViewById<AppCompatTextView>(R.id.wallet_name)
    private val balanceView = findViewById<AppCompatTextView>(R.id.wallet_balance)
    private val checkView = findViewById<AppCompatImageView>(R.id.check)
    private val typesView = findViewById<AppCompatTextView>(R.id.wallet_types)

    init {
        checkView.imageTintList = context.iconTertiaryColor.stateList
    }

    override fun onBind(item: Item.Account) {
        itemView.setOnClickListener { onClick(item) }
        itemView.background = ListCell.Position.SINGLE.drawable(context)

        colorView.backgroundTintList = ColorStateList.valueOf(item.color)
        emojiView.setEmoji(item.emoji, Color.TRANSPARENT)
        nameView.text = item.title
        balanceView.setText(Localization.customize)
        checkView.setImageResource(UIKitIcon.ic_chevron_right_16)
        typesView.text = context.getWalletBadges(item.walletType, item.walletVersion)

    }
}