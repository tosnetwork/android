package network.tos.wallet.app.ui.screen.staking.viewer.list.holder

import android.view.View
import android.view.ViewGroup
import androidx.core.net.toUri
import network.tos.extensions.uri
import network.tos.wallet.app.ui.screen.browser.dapp.DAppScreen
import network.tos.wallet.app.ui.screen.staking.stake.StakingScreen
import network.tos.wallet.app.ui.screen.staking.unstake.UnStakeScreen
import network.tos.wallet.app.ui.screen.staking.viewer.list.Item
import network.tos.wallet.app.R
import uikit.navigation.Navigation

class ActionsHolder(
    parent: ViewGroup,
): Holder<Item.Actions>(parent, R.layout.view_staking_actions) {

    private val navigation = Navigation.from(context)
    private val plusView = findViewById<View>(R.id.plus)
    private val minusView = findViewById<View>(R.id.minus)

    override fun onBind(item: Item.Actions) {
        plusView.isEnabled = !item.stakeDisabled
        minusView.isEnabled = !item.unstakeDisabled

        plusView.setOnClickListener {
            if (item.poolAddress != null) {
                navigation?.add(StakingScreen.newInstance(item.wallet, from = "staking_viewer", poolAddress = item.poolAddress))
            } else if (item.ethenaMethod != null) {
                navigation?.add(
                    DAppScreen.newInstance(
                        wallet = item.wallet,
                        title = item.ethenaMethod.name,
                        url = item.ethenaMethod.depositUrl.toUri(),
                        iconUrl = item.iconRes?.uri().toString(),
                        source = "staking_viewer",
                        forceConnect = true
                    )
                )
            }
        }

        minusView.setOnClickListener {
            if (item.poolAddress != null) {
                navigation?.add(UnStakeScreen.newInstance(item.wallet, item.poolAddress))
            } else if (item.ethenaMethod != null) {
                navigation?.add(
                    DAppScreen.newInstance(
                        wallet = item.wallet,
                        title = item.ethenaMethod.name,
                        url = item.ethenaMethod.withdrawalUrl.toUri(),
                        iconUrl = item.iconRes?.uri().toString(),
                        source = "staking_viewer",
                        forceConnect = true
                    )
                )
            }
        }
    }


}