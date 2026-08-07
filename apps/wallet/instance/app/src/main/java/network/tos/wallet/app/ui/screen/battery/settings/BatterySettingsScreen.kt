package network.tos.wallet.app.ui.screen.battery.settings

import android.os.Bundle
import android.view.View
import network.tos.wallet.app.koin.walletViewModel
import network.tos.wallet.app.ui.base.BaseHolderWalletScreen
import network.tos.wallet.app.ui.base.ScreenContext
import network.tos.wallet.app.ui.screen.battery.BatteryScreen
import network.tos.wallet.app.ui.screen.battery.BatteryViewModel
import network.tos.wallet.app.ui.screen.battery.settings.list.Adapter
import network.tos.uikit.icon.UIKitIcon
import network.tos.wallet.data.account.entities.WalletEntity
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.parameter.parametersOf
import uikit.R
import uikit.extensions.collectFlow
import uikit.extensions.getDimensionPixelSize

class BatterySettingsScreen(wallet: WalletEntity): BaseHolderWalletScreen.ChildListScreen<ScreenContext.Wallet, BatteryScreen, BatteryViewModel>(ScreenContext.Wallet(wallet)) {

    override val fragmentName: String = "BatterySettingsFragment"

    override val viewModel: BatterySettingsViewModel by walletViewModel()

    private val adapter = Adapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        collectFlow(viewModel.uiItemsFlow, adapter::submitList)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setCloseIcon(UIKitIcon.ic_chevron_left_16) { popBackStack() }
        setActionIcon(UIKitIcon.ic_close_16) { finish() }
        setAdapter(adapter)
        collectFlow(viewModel.titleFlow, ::setTitle)
    }

    companion object {

        fun newInstance(wallet: WalletEntity) = BatterySettingsScreen(wallet)
    }
}