package network.tos.wallet.app.ui.screen.staking.unstake

import network.tos.icu.Coins
import network.tos.wallet.data.staking.entities.PoolEntity

sealed class UnStakeEvent {

    data class OpenConfirm(
        val pool: PoolEntity,
        val amount: Coins
    ): UnStakeEvent()

    data object RouteToAmount: UnStakeEvent()

    data object Finish: UnStakeEvent()

}