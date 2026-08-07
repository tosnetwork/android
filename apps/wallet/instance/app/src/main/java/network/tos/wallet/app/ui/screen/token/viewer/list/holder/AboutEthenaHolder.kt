package network.tos.wallet.app.ui.screen.token.viewer.list.holder

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatTextView
import network.tos.wallet.app.helper.BrowserHelper
import network.tos.wallet.app.ui.screen.token.viewer.list.Item
import network.tos.wallet.app.R
import network.tos.uikit.color.textAccentColor
import network.tos.wallet.localization.Localization

class AboutEthenaHolder(parent: ViewGroup) :
    Holder<Item.AboutEthena>(parent, R.layout.view_about_usde) {

    private val descriptionView = findViewById<AppCompatTextView>(R.id.description)

    override fun onBind(item: Item.AboutEthena) {
        itemView.setOnClickListener {
            BrowserHelper.open(context, item.url)
        }

        val aboutText = getString(Localization.about_ethena)
        descriptionView.text = SpannableStringBuilder("${item.description} $aboutText").apply {
            setSpan(
                ForegroundColorSpan(context.textAccentColor),
                length - aboutText.length, length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

}