package network.tos.wallet.app.ui.screen.events.compose

import network.tos.wallet.app.ui.base.BaseWalletVM
import network.tos.wallet.app.ui.screen.dialog.encrypted.EncryptedCommentScreen
import network.tos.wallet.data.account.AccountRepository
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.events.CommentEncryption
import network.tos.wallet.data.events.EventsRepository
import network.tos.wallet.data.events.tx.model.TxEvent
import network.tos.wallet.data.passcode.PasscodeManager
import network.tos.wallet.data.settings.SettingsRepository
import network.tos.wallet.localization.Localization
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object TxScope {

    suspend fun BaseWalletVM.decryptComment(
        wallet: WalletEntity,
        tx: TxEvent,
        actionIndex: Int,
        accountRepository: AccountRepository,
        settingsRepository: SettingsRepository,
        passcodeManager: PasscodeManager,
        eventsRepository: EventsRepository,
    ): Boolean {
        try {
            val action = tx.actions[actionIndex]
            val encryptedText = action.encryptedText ?: return true
            val account = action.account ?: throw Exception("No account")
            if (settingsRepository.showEncryptedCommentModal) {
                val noShowAgain = withContext(Dispatchers.Main) {
                    EncryptedCommentScreen.show(context)
                } ?: throw Exception("User canceled")
                settingsRepository.showEncryptedCommentModal = !noShowAgain
            }
            if (!passcodeManager.confirmation(context, context.getString(Localization.app_name))) {
                throw Exception("Wrong passcode")
            }
            val privateKey = accountRepository.getPrivateKey(wallet.id) ?: throw Exception("Private key not found")

            val decrypted = CommentEncryption.decryptComment(
                wallet.publicKey,
                privateKey,
                encryptedText.cipher,
                account.address
            )
            eventsRepository.saveDecryptedComment(tx.hash, decrypted)
            return false
        } catch (_: Throwable) {
            return false
        }
    }
}