package network.tos.wallet.app.ui.screen.browser.search.list.holder

import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatTextView
import network.tos.wallet.app.ui.screen.browser.search.list.Item
import network.tos.wallet.app.R
import uikit.extensions.drawable

class LinkHolder(
    parent: ViewGroup,
    private val onClick: (title: String, url: String, iconUrl: String, sendAnalytics: Boolean) -> Unit
): Holder<Item.Link>(parent, R.layout.view_browser_search_link) {

    private val titleView = findViewById<AppCompatTextView>(R.id.title)
    private val subtitleView = findViewById<AppCompatTextView>(R.id.subtitle)

    override fun onBind(item: Item.Link) {
        itemView.background = item.position.drawable(context)
        itemView.setOnClickListener { onClick(item.title, item.url, "", false) }

        titleView.text = item.title
        subtitleView.text = item.url.replace("https://", "")
    }
}