package network.tos.wallet.app.ui.screen.collectibles.manage.list.holder

import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatTextView
import network.tos.wallet.app.ui.screen.collectibles.manage.list.Item
import network.tos.wallet.app.ui.screen.settings.security.SecurityScreen
import network.tos.wallet.app.R
import network.tos.wallet.localization.Localization
import uikit.extensions.getSpannable
import uikit.navigation.Navigation

class FooterHolder(parent: ViewGroup): Holder<Item.SafeMode>(parent, R.layout.view_item_safemode) {

    private val view = findViewById<AppCompatTextView>(R.id.view)

    init {
        view.text = context.getSpannable(Localization.safe_mode_tokens_footer)
    }

    override fun onBind(item: Item.SafeMode) {
        view.setOnClickListener {
            Navigation.from(context)?.add(SecurityScreen.newInstance(item.wallet))
        }
    }


}