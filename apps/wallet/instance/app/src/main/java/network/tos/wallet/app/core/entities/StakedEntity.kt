package network.tos.wallet.app.core.entities

import network.tos.blockchain.ton.extensions.equalsAddress
import network.tos.icu.Coins
import network.tos.wallet.api.API
import network.tos.wallet.api.entity.BalanceEntity
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.core.currency.WalletCurrency
import network.tos.wallet.data.rates.RatesRepository
import network.tos.wallet.data.staking.StakingPool
import network.tos.wallet.data.staking.entities.PoolEntity
import network.tos.wallet.data.staking.entities.StakingEntity
import network.tos.wallet.data.token.entities.AccountTokenEntity

data class StakedEntity(
    val pool: PoolEntity,
    val balance: Coins,
    val readyWithdraw: Coins,
    val fiatBalance: Coins,
    val fiatReadyWithdraw: Coins,
    val liquidToken: BalanceEntity? = null,
    val pendingDeposit: Coins,
    val pendingWithdraw: Coins,
    val cycleStart: Long,
    val cycleEnd: Long,
) {

    val isTonstakers: Boolean
        get() = pool.isTonstakers

    val maxApy: Boolean
        get() = pool.maxApy

    companion object {

        suspend fun create(
            wallet: WalletEntity,
            staking: StakingEntity,
            tokens: List<AccountTokenEntity>,
            currency: WalletCurrency,
            ratesRepository: RatesRepository,
            api: API,
        ): List<StakedEntity> {
            val fiatRates = ratesRepository.getTONRates(currency)
            val list = mutableListOf<StakedEntity>()
            val activePools = getActivePools(staking, tokens)
            for (pool in activePools) {
                if (pool.implementation == StakingPool.Implementation.LiquidTF) {
                    val isTonstakersAlready = list.any { it.isTonstakers }
                    if (isTonstakersAlready) {
                        continue
                    }

                    val liquidJettonMaster = pool.liquidJettonMaster ?: continue
                    val token = tokens.find { it.address.equalsAddress(liquidJettonMaster) } ?: continue
                    val rates = ratesRepository.getRates(WalletCurrency.TON, token.address)
                    val balance = rates.convert(token.address, token.balance.value)
                    val readyWithdraw = rates.convert(token.address, staking.getReadyWithdraw(pool))
                    val pendingDeposit = rates.convert(token.address, staking.getPendingDeposit(pool))
                    val pendingWithdraw = rates.convert(token.address, staking.getPendingWithdraw(pool))
                    list.add(StakedEntity(
                        pool = pool,
                        balance = balance,
                        fiatBalance = fiatRates.convertTON(balance),
                        readyWithdraw = readyWithdraw,
                        fiatReadyWithdraw = fiatRates.convertTON(readyWithdraw),
                        liquidToken = token.balance.copy(),
                        pendingDeposit = pendingDeposit,
                        pendingWithdraw = pendingWithdraw,
                        cycleStart = pool.cycleStart,
                        cycleEnd = pool.cycleEnd,
                    ))
                } else {
                    val balance = staking.getAmount(pool)
                    val readyWithdraw = staking.getReadyWithdraw(pool)
                    val pendingDeposit = staking.getPendingDeposit(pool)

                    list.add(StakedEntity(
                        pool = pool,
                        balance = balance,
                        fiatBalance = fiatRates.convertTON(balance),
                        readyWithdraw = readyWithdraw,
                        fiatReadyWithdraw = fiatRates.convertTON(readyWithdraw),
                        pendingDeposit = pendingDeposit,
                        pendingWithdraw = staking.getPendingWithdraw(pool),
                        cycleStart = pool.cycleStart,
                        cycleEnd = pool.cycleEnd,
                    ))
                }
            }
            return list
        }

        private fun getActivePools(
            staking: StakingEntity,
            tokens: List<AccountTokenEntity>
        ): List<PoolEntity> {
            val pools = mutableListOf<PoolEntity>()
            for (token in tokens.filter { staking.poolsJettonAddresses.contains(it.address) }) {
                staking.findPoolByTokenAddress(token.address)?.let { pools.add(it) }
            }

            for (info in staking.info) {
                staking.findPoolByAddress(info.pool)?.let { pools.add(it) }
            }
            return pools
        }
    }

}