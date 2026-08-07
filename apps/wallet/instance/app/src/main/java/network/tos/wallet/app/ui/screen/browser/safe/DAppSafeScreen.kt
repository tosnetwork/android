package network.tos.wallet.app.ui.screen.browser.safe

import androidx.compose.runtime.Composable
import network.tos.wallet.app.helper.BrowserHelper
import network.tos.wallet.app.ui.base.BaseWalletVM
import network.tos.wallet.app.ui.base.compose.ComposeWalletScreen
import network.tos.wallet.data.account.entities.WalletEntity
import org.koin.androidx.viewmodel.ext.android.viewModel
import uikit.base.BaseFragment

class DAppSafeScreen(wallet: WalletEntity) : ComposeWalletScreen(wallet), BaseFragment.Modal {

    override val fragmentName: String = "DAppSafeScreen"

    override val viewModel: BaseWalletVM.EmptyViewViewModel by viewModel()

    @Composable
    override fun ScreenContent() {
        DAppSafeComposable(
            onSafeClick = {
                BrowserHelper.open(requireActivity(), "https://tonkeeper.helpscoutdocs.com/")
            },
            onClose = { finish() }
        )
    }

    companion object {
        fun newInstance(wallet: WalletEntity) = DAppSafeScreen(wallet)
    }
}