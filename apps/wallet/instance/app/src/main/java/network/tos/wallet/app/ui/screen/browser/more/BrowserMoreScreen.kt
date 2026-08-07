package network.tos.wallet.app.ui.screen.browser.more

import android.os.Bundle
import android.util.Log
import android.view.View
import network.tos.wallet.app.koin.walletViewModel
import network.tos.wallet.app.ui.base.BaseListWalletScreen
import network.tos.wallet.app.ui.base.ScreenContext
import network.tos.wallet.app.ui.screen.browser.base.BrowserBaseScreen
import network.tos.wallet.app.ui.screen.browser.more.list.Adapter
import network.tos.wallet.data.account.entities.WalletEntity
import org.koin.core.parameter.parametersOf
import uikit.base.BaseFragment
import uikit.extensions.collectFlow

class BrowserMoreScreen(wallet: WalletEntity): BaseListWalletScreen<ScreenContext.Wallet>(ScreenContext.Wallet(wallet)), BaseFragment.SwipeBack {

    override val fragmentName: String = "BrowserMoreScreen"

    private val baseFragment: BrowserBaseScreen? by lazy {
        BrowserBaseScreen.from(this)
    }

    private val id: String by lazy {
        requireArguments().getString(ARG_ID)!!
    }

    override val viewModel: BrowserMoreViewModel by walletViewModel {
        parametersOf(id)
    }

    private val adapter = Adapter()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        collectFlow(viewModel.uiItemsFlow, adapter::submitList)
        collectFlow(viewModel.titleFlow, ::setTitle)
        setAdapter(adapter)
    }

    override fun finishInternal() {
        baseFragment?.removeFragment(this) ?: finish()
    }

    companion object {

        private const val ARG_ID = "id"

        fun newInstance(wallet: WalletEntity, id: String): BrowserMoreScreen {
            val fragment = BrowserMoreScreen(wallet)
            fragment.putStringArg(ARG_ID, id)
            return fragment
        }
    }

}