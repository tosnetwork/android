package network.tos.wallet.app.ui.screen.staking.stake.options

import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import network.tos.wallet.app.ui.base.BaseHolderWalletScreen
import network.tos.wallet.app.ui.base.ScreenContext
import network.tos.wallet.app.ui.screen.staking.stake.StakingScreen
import network.tos.wallet.app.ui.screen.staking.stake.StakingViewModel
import network.tos.wallet.app.ui.screen.staking.stake.details.StakeDetailsFragment
import network.tos.wallet.app.ui.screen.staking.stake.options.list.Adapter
import network.tos.wallet.app.ui.screen.staking.stake.options.list.Item
import network.tos.wallet.app.ui.screen.staking.stake.pool.StakePoolFragment
import network.tos.wallet.app.ui.screen.staking.unstake.UnStakeScreen
import network.tos.wallet.app.ui.screen.staking.unstake.UnStakeViewModel
import network.tos.wallet.app.R
import network.tos.uikit.icon.UIKitIcon
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.staking.entities.PoolInfoEntity
import network.tos.wallet.localization.Localization
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import uikit.extensions.getDimensionPixelSize
import uikit.extensions.setPaddingHorizontal

class StakeOptionsFragment(wallet: WalletEntity): BaseHolderWalletScreen.ChildListScreen<ScreenContext.Wallet, StakingScreen, StakingViewModel>(ScreenContext.Wallet(wallet)) {

    override val fragmentName: String = "StakeOptionsFragment"

    private val adapter = Adapter { info ->
        openPool(info)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        combine(
            primaryViewModel.poolsFlow,
            primaryViewModel.selectedPoolFlow
        ) { pools, selectedPool ->
            val uniquePools = pools.distinctBy { it.implementation }
            Item.map(uniquePools, selectedPool)
        }.onEach(adapter::submitList).launchIn(lifecycleScope)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setAdapter(adapter)
        setCloseIcon(UIKitIcon.ic_chevron_left_16) { popBackStack() }
        setActionIcon(UIKitIcon.ic_close_16) { finish() }
        setTitle(getString(Localization.staking_options))
    }

    private fun openPool(info: PoolInfoEntity) {
        if (info.pools.size > 1) {
            setFragment(StakePoolFragment.newInstance(screenContext.wallet, info))
        } else {
            val singlePool = info.pools.firstOrNull() ?: return
            setFragment(StakeDetailsFragment.newInstance(info, singlePool.address))
        }
    }

    companion object {
        fun newInstance(wallet: WalletEntity) = StakeOptionsFragment(wallet)
    }
}