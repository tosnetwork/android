package network.tos.wallet.app.ui.screen.wallet.main.list.holder

import android.view.ViewGroup
import android.widget.Button
import network.tos.wallet.app.ui.screen.wallet.main.list.Item
import network.tos.wallet.app.ui.screen.wallet.manage.TokensManageScreen
import network.tos.wallet.app.R
import uikit.navigation.Navigation

class ManageHolder(
    parent: ViewGroup
): Holder<Item.Manage>(parent, R.layout.view_wallet_manage) {

    private val button = findViewById<Button>(R.id.button)

    override fun onBind(item: Item.Manage) {
        button.setOnClickListener {
            Navigation.from(context)?.add(TokensManageScreen.newInstance(item.wallet))
        }
    }

}