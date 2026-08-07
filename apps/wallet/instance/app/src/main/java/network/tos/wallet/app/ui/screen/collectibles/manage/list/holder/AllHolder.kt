package network.tos.wallet.app.ui.screen.collectibles.manage.list.holder

import android.view.View
import android.view.ViewGroup
import network.tos.wallet.app.ui.screen.collectibles.manage.list.Item
import network.tos.wallet.app.R

class AllHolder(
    parent: ViewGroup,
    private val showAllClick: () -> Unit
): Holder<Item.All>(parent, R.layout.view_item_show_all) {

    private val button = findViewById<View>(R.id.button)

    init {
        button.setOnClickListener { showAllClick() }
    }

    override fun onBind(item: Item.All) {

    }

}