package network.tos.wallet.app.ui.screen.backup.main

import android.os.Bundle
import android.view.View
import network.tos.extensions.bestMessage
import network.tos.wallet.app.extensions.toast
import network.tos.wallet.app.koin.walletViewModel
import network.tos.wallet.app.ui.base.BaseListWalletScreen
import network.tos.wallet.app.ui.base.ScreenContext
import network.tos.wallet.app.ui.screen.backup.main.list.Adapter
import network.tos.wallet.app.ui.screen.backup.main.list.Item
import network.tos.wallet.app.ui.screen.phrase.PhraseScreen
import network.tos.wallet.app.BuildConfig
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.localization.Localization
import uikit.base.BaseFragment
import uikit.extensions.collectFlow

class BackupScreen(wallet: WalletEntity): BaseListWalletScreen<ScreenContext.Wallet>(ScreenContext.Wallet(wallet)), BaseFragment.SwipeBack {

    override val fragmentName: String = "BackupScreen"

    private val attentionDialog: BackupAttentionDialog by lazy {
        BackupAttentionDialog(requireContext())
    }

    override val viewModel: BackupViewModel by walletViewModel()

    private val adapter = Adapter { item ->
        when (item) {
            is Item.RecoveryPhrase -> attentionDialog.show {
                openRecoveryPhrase()
            }
            is Item.ManualBackup, Item.ManualAccentBackup -> attentionDialog.show {
                openRecoveryPhrase(backup = true)
            }
            is Item.Backup -> attentionDialog.show {
                openRecoveryPhrase(backup = true, backupId = item.entity.id)
            }
            else -> { }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        collectFlow(viewModel.uiItemsFlow, adapter::submitList)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setAdapter(adapter)
        setTitle(getString(Localization.backup))
    }

    private fun openRecoveryPhrase(backup: Boolean = false, backupId: Long = 0) {
        viewModel.getRecoveryPhrase(requireContext()) { words, error ->
            if (error != null) {
                navigation?.toast(error.bestMessage)
            } else {
                navigation?.add(PhraseScreen.newInstance(screenContext.wallet, words, backup, backupId))
            }
        }
    }

    companion object {
        fun newInstance(wallet: WalletEntity) = BackupScreen(wallet)
    }

}