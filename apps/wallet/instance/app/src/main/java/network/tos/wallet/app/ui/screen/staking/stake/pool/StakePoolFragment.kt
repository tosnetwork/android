package network.tos.wallet.app.ui.screen.staking.stake.pool

import android.os.Bundle
import android.view.View
import network.tos.extensions.getParcelableCompat
import network.tos.wallet.app.ui.base.BaseHolderWalletScreen
import network.tos.wallet.app.ui.base.ScreenContext
import network.tos.wallet.app.ui.screen.staking.stake.StakingScreen
import network.tos.wallet.app.ui.screen.staking.stake.StakingViewModel
import network.tos.wallet.app.ui.screen.staking.stake.details.StakeDetailsFragment
import network.tos.wallet.app.ui.screen.staking.stake.pool.list.Adapter
import network.tos.wallet.app.ui.screen.staking.stake.pool.list.Item
import network.tos.uikit.icon.UIKitIcon
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.staking.StakingPool
import network.tos.wallet.data.staking.entities.PoolInfoEntity
import uikit.extensions.collectFlow

class StakePoolFragment(wallet: WalletEntity): BaseHolderWalletScreen.ChildListScreen<ScreenContext.Wallet, StakingScreen, StakingViewModel>(ScreenContext.Wallet(wallet)) {

    override val fragmentName: String = "StakePoolFragment"

    private val info: PoolInfoEntity by lazy { requireArguments().getParcelableCompat(ARG_INFO)!! }

    private val adapter = Adapter { pool ->
        setFragment(StakeDetailsFragment.newInstance(info, pool.address))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        collectFlow(primaryViewModel.selectedPoolFlow) { pool ->
            adapter.submitList(Item.map(info, pool.address))
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setAdapter(adapter)
        setCloseIcon(UIKitIcon.ic_chevron_left_16) { popBackStack() }
        setActionIcon(UIKitIcon.ic_close_16) { finish() }
        setTitle(getString(StakingPool.getTitle(info.implementation)))
    }

    companion object {

        private const val ARG_INFO = "info"

        fun newInstance(wallet: WalletEntity, info: PoolInfoEntity): StakePoolFragment {
            val fragment = StakePoolFragment(wallet)
            fragment.putParcelableArg(ARG_INFO, info)
            return fragment
        }

    }
}