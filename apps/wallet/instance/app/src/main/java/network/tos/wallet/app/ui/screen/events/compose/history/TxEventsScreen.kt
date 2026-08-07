package network.tos.wallet.app.ui.screen.events.compose.history

import android.os.Bundle
import android.view.View
import androidx.compose.runtime.Composable
import network.tos.wallet.app.koin.walletViewModel
import network.tos.wallet.app.ui.base.compose.ComposeWalletScreen
import network.tos.wallet.app.ui.screen.events.compose.history.ui.TxEventComposable
import network.tos.wallet.data.account.entities.WalletEntity

class TxEventsScreen(wallet: WalletEntity) : ComposeWalletScreen(wallet) {

    override val viewModel: TxEventsViewModel by walletViewModel()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
    }

    @Composable
    override fun ScreenContent() = TxEventComposable(viewModel)

    companion object {

        fun newInstance(wallet: WalletEntity) = TxEventsScreen(wallet)
    }
}