package network.tos.wallet.data.battery.source

import android.util.Log
import network.tos.icu.Coins
import network.tos.wallet.api.API
import network.tos.wallet.data.battery.entity.BatteryBalanceEntity
import network.tos.wallet.data.battery.entity.BatteryConfigEntity
import network.tos.wallet.data.battery.entity.RechargeMethodEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

internal class RemoteDataSource(
    private val api: API
) {

    suspend fun fetchBalance(
        tonProofToken: String,
        testnet: Boolean
    ): BatteryBalanceEntity? = withContext(Dispatchers.IO) {
        val response = api.getBatteryBalance(tonProofToken, testnet) ?: return@withContext null

        BatteryBalanceEntity(
            balance = Coins.of(response.balance.toBigDecimal(), 20),
            reservedBalance = Coins.of(response.reserved.toBigDecimal(), 20)
        )
    }

    suspend fun fetchConfig(
        testnet: Boolean
    ): BatteryConfigEntity? = withContext(Dispatchers.IO) {
        val configDeferred = async { api.getBatteryConfig(testnet) }
        val rechargeMethodsDeferred = async { api.getBatteryRechargeMethods(testnet) }

        val config = configDeferred.await() ?: return@withContext null
        val rechargeMethods = rechargeMethodsDeferred.await() ?: return@withContext null

        BatteryConfigEntity(
            excessesAccount = config.excessAccount,
            fundReceiver = config.fundReceiver,
            rechargeMethods = rechargeMethods.methods.map(::RechargeMethodEntity),
            gasProxy = config.gasProxy.map { it.address },
            meanPrices = BatteryConfigEntity.MeanPrices(
                batteryMeanPriceSwap = config.meanPrices.batteryMeanPriceSwap,
                batteryMeanPriceJetton = config.meanPrices.batteryMeanPriceJetton,
                batteryMeanPriceNft = config.meanPrices.batteryMeanPriceNft,
                batteryMeanPriceTronUsdt = config.meanPrices.batteryMeanPriceTronUsdt
            ),
            chargeCost = config.chargeCost,
            reservedAmount = config.batteryReservedAmount
        )
    }

}