package network.tos.wallet.app.ui.screen.wallet.manage.list.holder

import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatTextView
import network.tos.wallet.app.ui.screen.settings.security.SecurityScreen
import network.tos.wallet.app.ui.screen.wallet.manage.TokensManageScreen
import network.tos.wallet.app.ui.screen.wallet.manage.list.Item
import network.tos.wallet.app.R
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.localization.Localization
import uikit.extensions.getSpannable
import uikit.navigation.Navigation

class FooterHolder(parent: ViewGroup): Holder<Item.SafeMode>(parent, R.layout.view_item_safemode) {

    private val view = findViewById<AppCompatTextView>(R.id.view)
    private val navigation: Navigation?
        get() = Navigation.from(context)

    init {
        view.text = context.getSpannable(Localization.safe_mode_tokens_footer)
    }

    private fun openSecurityScreen(wallet: WalletEntity) {
        val nav = navigation ?: return
        nav.removeByClass({
            nav.add(SecurityScreen.newInstance(wallet))
        }, TokensManageScreen::class.java)
    }

    override fun onBind(item: Item.SafeMode) {
        view.setOnClickListener { openSecurityScreen(item.wallet) }
    }

}