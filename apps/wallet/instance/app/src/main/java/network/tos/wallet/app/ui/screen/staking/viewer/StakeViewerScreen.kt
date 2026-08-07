package network.tos.wallet.app.ui.screen.staking.viewer

import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import network.tos.wallet.app.extensions.toast
import network.tos.wallet.app.koin.walletViewModel
import network.tos.wallet.app.ui.base.BaseListWalletScreen
import network.tos.wallet.app.ui.base.ScreenContext
import network.tos.wallet.app.ui.screen.staking.viewer.list.Adapter
import network.tos.wallet.api.entity.EthenaEntity
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.localization.Localization
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.koin.core.parameter.parametersOf
import uikit.base.BaseFragment
import uikit.extensions.collectFlow

class StakeViewerScreen(wallet: WalletEntity): BaseListWalletScreen<ScreenContext.Wallet>(ScreenContext.Wallet(wallet)), BaseFragment.SwipeBack {

    override val fragmentName: String = "StakeViewerScreen"

    override val viewModel: StakeViewerViewModel by walletViewModel { parametersOf(args.address, args.ethenaType) }

    private val args: StakeViewerArgs by lazy { StakeViewerArgs(requireArguments()) }

    private val adapter = Adapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.uiItemsFlow.catch {
            navigation?.toast(Localization.unknown_error)
            finish()
        }.onEach(adapter::submitList).launchIn(lifecycleScope)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val title = when {
            args.ethenaType.isNotEmpty() -> getString(Localization.staked_usde)
            else -> args.name
        }
        setTitle(title)
        setAdapter(adapter)
        collectFlow(viewModel.poolNameFlow, ::setTitle)
    }

    companion object {

        fun newInstance(wallet: WalletEntity, address: String, name: String): StakeViewerScreen {
            val fragment = StakeViewerScreen(wallet)
            fragment.setArgs(StakeViewerArgs(address, name, ""))
            return fragment
        }

        fun newInstance(wallet: WalletEntity, ethenaType: EthenaEntity.Method.Type): StakeViewerScreen {
            val fragment = StakeViewerScreen(wallet)
            fragment.setArgs(StakeViewerArgs(ethenaType = ethenaType.id, address = "", name = ""))
            return fragment
        }
    }
}