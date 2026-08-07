package network.tos.wallet.app.ui.screen.browser.search.list.holder

import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatTextView
import network.tos.wallet.app.ui.screen.browser.dapp.DAppScreen
import network.tos.wallet.app.ui.screen.browser.search.list.Item
import network.tos.wallet.app.R
import uikit.extensions.drawable
import uikit.navigation.Navigation

class SearchHolder(
    parent: ViewGroup,
    private val onClick: (title: String, url: String, iconUrl: String, sendAnalytics: Boolean) -> Unit
): Holder<Item.Search>(parent, R.layout.view_browser_search_query) {

    private val titleView = findViewById<AppCompatTextView>(R.id.title)

    override fun onBind(item: Item.Search) {
        itemView.background = item.position.drawable(context)
        itemView.setOnClickListener { onClick(item.query, item.url, "", false) }
        titleView.text = item.query
    }
}