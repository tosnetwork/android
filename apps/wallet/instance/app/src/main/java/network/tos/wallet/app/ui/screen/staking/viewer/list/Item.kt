package network.tos.wallet.app.ui.screen.staking.viewer.list

import android.net.Uri
import network.tos.icu.Coins
import network.tos.wallet.app.R
import network.tos.uikit.list.BaseListItem
import network.tos.wallet.api.entity.EthenaEntity
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.staking.StakingPool

sealed class Item(type: Int): BaseListItem(type) {

    companion object {
        const val TYPE_BALANCE = 0
        const val TYPE_ACTIONS = 1
        const val TYPE_DETAILS = 3
        const val TYPE_LINKS = 4
        const val TYPE_TOKEN = 5
        const val TYPE_SPACE = 6
        const val TYPE_DESCRIPTION = 7
        const val TYPE_ETHENA_DETAILS = 8
    }

    data class Balance(
        val poolImplementation: StakingPool.Implementation? = null,
        val ethenaType: EthenaEntity.Method.Type? = null,
        val balance: Coins,
        val balanceFormat: CharSequence,
        val fiat: Coins,
        val fiatFormat: CharSequence,
        val hiddenBalance: Boolean,
    ): Item(TYPE_BALANCE) {

        val currencyIcon: Int by lazy {
            if (ethenaType != null) {
                network.tos.wallet.api.R.drawable.ic_udse_ethena_with_bg
            } else {
                network.tos.wallet.api.R.drawable.ic_ton_with_bg
            }
        }

        val iconRes: Int?
            get() {
                if (poolImplementation != null) {
                    return StakingPool.getIcon(poolImplementation)
                } else {
                    return when (ethenaType) {
                        EthenaEntity.Method.Type.STONFI -> R.drawable.ethena
                        EthenaEntity.Method.Type.AFFLUENT -> R.drawable.affluent
                        null -> null
                    }
                }
            }
    }

    data class Actions(
        val wallet: WalletEntity,
        val stakeDisabled: Boolean = false,
        val unstakeDisabled: Boolean = false,
        val poolAddress: String? = null,
        val ethenaMethod: EthenaEntity.Method? = null,
    ): Item(TYPE_ACTIONS) {
        val iconRes: Int?
            get() {
                return when (ethenaMethod?.type) {
                    EthenaEntity.Method.Type.STONFI -> R.drawable.stonfi
                    EthenaEntity.Method.Type.AFFLUENT -> R.drawable.affluent
                    null -> null
                }
            }
    }

    data class Details(
        val apyFormat: String,
        val minDepositFormat: CharSequence,
        val maxApy: Boolean,
    ): Item(TYPE_DETAILS)

    data class Links(
        val links: List<String>
    ): Item(TYPE_LINKS)

    data class Token(
        val wallet: WalletEntity,
        val iconUri: Uri,
        val address: String,
        val symbol: String,
        val name: String,
        val balance: Coins,
        val balanceFormat: CharSequence,
        val fiat: Coins,
        val fiatFormat: CharSequence,
        val rate: CharSequence,
        val rateDiff24h: String,
        val verified: Boolean,
        val testnet: Boolean,
        val hiddenBalance: Boolean,
        val blacklist: Boolean
    ): Item(TYPE_TOKEN)

    data object Space: Item(TYPE_SPACE)

    data class Description(
        val description: String,
        val uri: Uri? = null,
        val isEthena: Boolean = false,
    ): Item(TYPE_DESCRIPTION)

    data class EthenaDetails(
        val apyFormat: CharSequence,
        val apyTitle: String,
        val apyDescription: String,
        val bonusApyFormat: CharSequence? = null,
        val bonusTitle: String? = null,
        val bonusDescription: String? = null,
        val bonusUrl: String? = null,
        val faqUrl: String,
    ): Item(TYPE_ETHENA_DETAILS)

}