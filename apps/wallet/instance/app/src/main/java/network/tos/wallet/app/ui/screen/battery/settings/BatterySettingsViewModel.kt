package network.tos.wallet.app.ui.screen.battery.settings

import android.app.Application
import network.tos.wallet.app.ui.base.BaseWalletVM
import network.tos.wallet.app.ui.screen.battery.settings.list.Item
import network.tos.uikit.list.ListCell
import network.tos.wallet.api.API
import network.tos.wallet.api.entity.ConfigEntity
import network.tos.wallet.data.account.AccountRepository
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.battery.BatteryMapper
import network.tos.wallet.data.battery.BatteryRepository
import network.tos.wallet.data.battery.entity.BatteryBalanceEntity
import network.tos.wallet.data.battery.entity.BatteryConfigEntity
import network.tos.wallet.data.settings.BatteryTransaction
import network.tos.wallet.data.settings.SettingsRepository
import network.tos.wallet.localization.Localization
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

class BatterySettingsViewModel(
    app: Application,
    private val wallet: WalletEntity,
    private val accountRepository: AccountRepository,
    private val settingsRepository: SettingsRepository,
    private val batteryRepository: BatteryRepository,
    private val api: API,
) : BaseWalletVM(app) {

    val tronUsdtEnabled: Boolean
        get() = settingsRepository.getTronUsdtEnabled(wallet.id)

    val titleFlow = batteryRepository.balanceUpdatedFlow.map { _ ->
        val batteryBalance = getBatteryBalance(wallet)
        val hasBalance = batteryBalance.balance.isPositive

        if (hasBalance) {
            ""
        } else {
            getString(Localization.transactions)
        }
    }.flowOn(Dispatchers.IO)

    val uiItemsFlow = batteryRepository.balanceUpdatedFlow.map { _ ->
        val batteryBalance = getBatteryBalance(wallet)
        val batteryConfig = getBatteryConfig(wallet)
        val hasBalance = batteryBalance.balance.isPositive

        val uiItems = mutableListOf<Item>()
        if (hasBalance) {
            uiItems.add(Item.SettingsHeader)
        }

        val types = BatteryTransaction.entries

        val size = if (tronUsdtEnabled) {
            types.size + 1
        } else {
            types.size
        }

        for ((index, type) in types.withIndex()) {
            val position = ListCell.getPosition(size, index)
            val meanPrice = getTransactionMeanPrice(batteryConfig.meanPrices, type)
            val enabled = settingsRepository.batteryIsEnabledTx(wallet.accountId, type)
            val item = Item.SupportedTransaction(
                wallet = wallet,
                position = position,
                supportedTransaction = type,
                enabled = enabled,
                showToggle = hasBalance,
                changes = meanPrice,
            )
            uiItems.add(item)
        }

        if (tronUsdtEnabled) {
            uiItems.add(
                Item.SupportedTransaction(
                    wallet = wallet,
                    position = ListCell.Position.LAST,
                    supportedTransaction = BatteryTransaction.TRC20,
                    enabled = true,
                    showToggle = hasBalance,
                    changes = batteryConfig.meanPrices.batteryMeanPriceTronUsdt ?: 0,
                )
            )
        }

        uiItems.toList()
    }.flowOn(Dispatchers.IO)

    private suspend fun getBatteryBalance(
        wallet: WalletEntity
    ): BatteryBalanceEntity {
        val tonProofToken =
            accountRepository.requestTonProofToken(wallet) ?: return BatteryBalanceEntity.Empty
        return batteryRepository.getBalance(
            tonProofToken = tonProofToken,
            publicKey = wallet.publicKey,
            testnet = wallet.testnet
        )
    }

    private suspend fun getBatteryConfig(
        wallet: WalletEntity
    ): BatteryConfigEntity {
        return batteryRepository.getConfig(wallet.testnet)
    }

    private fun getTransactionMeanPrice(
        meanPrices: BatteryConfigEntity.MeanPrices,
        transaction: BatteryTransaction
    ): Int {
        return when (transaction) {
            BatteryTransaction.NFT -> meanPrices.batteryMeanPriceNft
            BatteryTransaction.SWAP -> meanPrices.batteryMeanPriceSwap
            BatteryTransaction.JETTON -> meanPrices.batteryMeanPriceJetton
            else -> 0
        }
    }
}