package network.tos.wallet.app.ui.screen.settings.extensions

import android.os.Bundle
import android.view.View
import network.tos.wallet.app.koin.walletViewModel
import network.tos.wallet.app.ui.base.BaseListWalletScreen
import network.tos.wallet.app.ui.base.ScreenContext
import network.tos.wallet.app.ui.screen.send.boc.RemoveExtensionScreen
import network.tos.wallet.app.ui.screen.settings.extensions.list.Adapter
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.localization.Localization
import uikit.base.BaseFragment
import uikit.extensions.collectFlow

class ExtensionsScreen(private val wallet: WalletEntity): BaseListWalletScreen<ScreenContext.Wallet>(ScreenContext.Wallet(wallet)), BaseFragment.SwipeBack {

    override val fragmentName: String = "ExtensionsScreen"

    override val viewModel: ExtensionsViewModel by walletViewModel()

    private val adapter = Adapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        collectFlow(viewModel.uiItemsFlow, adapter::submitList)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setTitle(getString(Localization.installed_extensions))
        setAdapter(adapter)
    }

    companion object {
        fun newInstance(wallet: WalletEntity) = ExtensionsScreen(wallet)
    }
}


