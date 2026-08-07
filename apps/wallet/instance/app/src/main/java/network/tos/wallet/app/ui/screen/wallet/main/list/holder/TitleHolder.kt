package network.tos.wallet.app.ui.screen.wallet.main.list.holder

import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatTextView
import network.tos.wallet.app.ui.screen.wallet.main.list.Item
import network.tos.wallet.app.R

class TitleHolder(parent: ViewGroup): Holder<Item.Title>(parent, R.layout.view_wallet_title) {

    private val titleView = findViewById<AppCompatTextView>(R.id.title)

    override fun onBind(item: Item.Title) {
        titleView.text = item.title
    }
}