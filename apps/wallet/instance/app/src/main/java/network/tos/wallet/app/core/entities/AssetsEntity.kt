package network.tos.wallet.app.core.entities

import network.tos.icu.Coins
import network.tos.wallet.api.entity.BalanceEntity
import network.tos.wallet.api.entity.value.Blockchain
import network.tos.wallet.api.entity.TokenEntity
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.core.currency.WalletCurrency
import network.tos.wallet.data.settings.SettingsRepository
import network.tos.wallet.data.settings.entities.TokenPrefsEntity
import network.tos.wallet.data.token.entities.AccountTokenEntity

sealed class AssetsEntity(
    val fiat: Coins,
) {

    companion object {

        suspend fun List<AssetsEntity>.sort(
            wallet: WalletEntity,
            settingsRepository: SettingsRepository
        ): List<AssetsEntity> {
            return map { asset ->
                val pref = if (asset is Token) {
                    settingsRepository.getTokenPrefs(wallet.id, asset.token.address, asset.token.blacklist)
                } else {
                    TokenPrefsEntity()
                }
                AssetsExtendedEntity(asset, pref, wallet.accountId)
            }.filter { !it.hidden }.sortedWith(AssetsExtendedEntity.comparator).map { it.raw }
        }
    }

    data class Staked(val staked: StakedEntity): AssetsEntity(staked.fiatBalance) {

        val isTonstakers: Boolean
            get() = staked.isTonstakers

        val liquidToken: BalanceEntity?
            get() = staked.liquidToken

        val readyWithdraw: Coins
            get() = staked.readyWithdraw
    }

    data class Token(
        val token: AccountTokenEntity
    ): AssetsEntity(token.fiat) {

        val address: String
            get() = token.address

        val decimals: Int
            get() = token.decimals

        val balance: Coins
            get() = token.balance.value

        val symbol: String
            get() = token.symbol

        val blockchain: Blockchain
            get() = token.balance.token.blockchain

        constructor(token: TokenEntity): this(
            AccountTokenEntity.createEmpty(token, "")
        )
    }

    data class Currency(
        val currency: WalletCurrency,
        val coins: Coins = Coins.ZERO
    ): AssetsEntity(coins)
}