package network.tos.wallet.app.ui.screen.watchonly

import androidx.compose.runtime.Composable
import network.tos.wallet.app.ui.base.BaseWalletVM
import network.tos.wallet.app.ui.base.compose.ComposeWalletScreen
import network.tos.wallet.app.ui.screen.init.InitArgs
import network.tos.wallet.app.ui.screen.init.InitScreen
import network.tos.wallet.data.account.entities.WalletEntity
import org.koin.androidx.viewmodel.ext.android.viewModel
import uikit.base.BaseFragment

class WatchInfoScreen(wallet: WalletEntity) : ComposeWalletScreen(wallet), BaseFragment.Modal {

    override val fragmentName: String = "WatchInfoScreen"

    override val viewModel: BaseWalletVM.EmptyViewViewModel by viewModel()

    @Composable
    override fun ScreenContent() {
        SupportComposable(
            onRecoveryClick = {
                navigation?.add(InitScreen.newInstance(type = InitArgs.Type.Import, watchRecoveryAccountId = wallet.accountId))
                finish()
            },
            onContinueClick = { finish() },
        )
    }

    companion object {
        fun newInstance(wallet: WalletEntity): WatchInfoScreen {
            return WatchInfoScreen(wallet)
        }
    }
}