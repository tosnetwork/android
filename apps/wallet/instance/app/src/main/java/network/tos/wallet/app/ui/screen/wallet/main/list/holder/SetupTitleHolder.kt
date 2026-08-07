package network.tos.wallet.app.ui.screen.wallet.main.list.holder

import android.view.View
import android.view.ViewGroup
import network.tos.wallet.app.koin.settingsRepository
import network.tos.wallet.app.ui.screen.wallet.main.list.Item
import network.tos.wallet.app.R

class SetupTitleHolder(parent: ViewGroup): Holder<Item.SetupTitle>(parent, R.layout.view_wallet_setup_title) {

    private val settingsRepository = context.settingsRepository

    private val doneButton = findViewById<View>(R.id.done)

    override fun onBind(item: Item.SetupTitle) {
        doneButton.visibility = if (item.showDone) View.VISIBLE else View.GONE
        doneButton.setOnClickListener {
            settingsRepository?.setupHide(item.walletId)
        }
    }

}