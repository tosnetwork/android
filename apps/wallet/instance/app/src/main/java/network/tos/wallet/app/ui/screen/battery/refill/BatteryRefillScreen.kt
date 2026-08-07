package network.tos.wallet.app.ui.screen.battery.refill

import android.os.Bundle
import android.view.View
import network.tos.wallet.app.koin.walletViewModel
import network.tos.wallet.app.ui.base.BaseHolderWalletScreen
import network.tos.wallet.app.ui.base.ScreenContext
import network.tos.wallet.app.ui.screen.battery.BatteryScreen
import network.tos.wallet.app.ui.screen.battery.BatteryViewModel
import network.tos.wallet.app.ui.screen.battery.refill.list.Adapter
import network.tos.uikit.icon.UIKitIcon
import network.tos.wallet.data.account.entities.WalletEntity
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.parameter.parametersOf
import uikit.R
import uikit.extensions.collectFlow
import uikit.extensions.getDimensionPixelSize

class BatteryRefillScreen(wallet: WalletEntity) : BaseHolderWalletScreen.ChildListScreen<ScreenContext.Wallet, BatteryScreen, BatteryViewModel>(ScreenContext.Wallet(wallet)) {

    override val fragmentName: String = "BatteryRefillScreen"

    override val viewModel: BatteryRefillViewModel by walletViewModel()

    private val adapter = Adapter(
        openSettings = { primaryViewModel.routeToSettings() },
        onSubmitPromo = { viewModel.submitPromo(it) },
        onPackSelect = { viewModel.makePurchase(it, requireActivity()) },
        onRestorePurchases = { viewModel.restorePurchases() }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        collectFlow(viewModel.uiItemsFlow, adapter::submitList)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setHeaderBackground(R.drawable.bg_page_gradient)
        setActionIcon(UIKitIcon.ic_close_16) { finish() }
        setAdapter(adapter)
        arguments?.getString(ARG_PROMO)?.let {
            viewModel.applyPromo(it)
        }
    }

    companion object {
        private const val ARG_PROMO = "promo"

        fun newInstance(wallet: WalletEntity) = BatteryRefillScreen(wallet)

        fun newInstance(wallet: WalletEntity, promo: String?): BatteryRefillScreen {
            val fragment = BatteryRefillScreen(wallet)
            fragment.putStringArg(ARG_PROMO, promo)
            return fragment
        }
    }
}