package network.tos.wallet.app.ui.screen.name.edit

import android.app.Application
import network.tos.wallet.app.core.FirebaseHelper
import network.tos.wallet.app.ui.base.BaseWalletVM
import network.tos.wallet.app.worker.WidgetUpdaterWorker
import network.tos.wallet.data.account.AccountRepository
import network.tos.wallet.data.account.entities.WalletEntity

class EditNameViewModel(
    app: Application,
    private val wallet: WalletEntity,
    private val accountRepository: AccountRepository
): BaseWalletVM(app) {

    fun save(name: String, emoji: CharSequence, color: Int) {
        FirebaseHelper.setTitleEmoji(emoji.toString())
        accountRepository.editLabel(
            walletId = wallet.id,
            name = name,
            emoji = emoji,
            color = color
        )

        WidgetUpdaterWorker.update(context)
    }
}