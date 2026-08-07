package network.tos.wallet.app.ui.screen.backup.main.list.holder

import android.view.ViewGroup
import network.tos.wallet.app.ui.screen.backup.main.list.Item
import network.tos.wallet.app.R
import uikit.extensions.dp

class SpaceHolder(parent: ViewGroup): Holder<Item.Space>(parent, R.layout.view_backup_space) {

    init {
        itemView.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 24.dp)
    }

    override fun onBind(item: Item.Space) {

    }
}