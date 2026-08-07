package network.tos.wallet.app.usecase.emulation

import network.tos.blockchain.ton.extensions.toRawAddress
import network.tos.icu.Coins
import network.tos.icu.CurrencyFormatter
import network.tos.wallet.app.core.Fee
import network.tos.wallet.app.helper.BatteryHelper
import network.tos.wallet.app.ui.screen.send.main.state.SendFee
import network.tos.wallet.api.API
import network.tos.wallet.api.entity.TokenEntity
import network.tos.wallet.data.account.AccountRepository
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.battery.BatteryMapper
import network.tos.wallet.data.battery.BatteryRepository
import network.tos.wallet.data.core.entity.TransferType
import network.tos.wallet.data.core.currency.WalletCurrency
import network.tos.wallet.data.rates.RatesRepository
import network.tos.wallet.data.token.TokenRepository
import io.tonapi.models.JettonQuantity
import io.tonapi.models.MessageConsequences
import kotlin.math.abs

data class Emulated(
    val consequences: MessageConsequences?,
    val type: TransferType,
    val total: Total,
    val extra: Extra,
    val currency: WalletCurrency,
    val failed: Boolean = false,
    val error: Throwable? = null,
) {

    companion object {
        val defaultExtra = Extra(false, Coins.ONE, Coins.ONE)

        suspend fun Emulated.buildFee(
            wallet: WalletEntity,
            api: API,
            accountRepository: AccountRepository,
            batteryRepository: BatteryRepository,
            ratesRepository: RatesRepository
        ): SendFee {
            val fee = Fee(extra.value, extra.isRefund)
            return SendFee.Ton(
                amount = fee,
                fiatAmount = Coins.ZERO,
                fiatCurrency = currency,
            )
        }
    }

    val nftCount: Int
        get() = total.nftCount

    val totalFormat: CharSequence
        get() = CurrencyFormatter.format(currency.code, total.totalFiat)

    val withBattery: Boolean
        get() = type == TransferType.Battery || type == TransferType.Gasless

    val totalTon: Coins
        get() = consequences?.let {
            Coins.of(it.risk.ton)
        } ?: Coins.ZERO

    val totalFees: Coins
        get() = consequences?.let {
            Coins.of(it.trace.transaction.totalFees)
        } ?: Coins.ZERO

    val jettons: List<JettonQuantity>
        get() = consequences?.risk?.jettons ?: emptyList()

    data class Total(
        val totalFiat: Coins,
        val nftCount: Int,
        val isDangerous: Boolean,
    )

    data class Extra(
        val isRefund: Boolean,
        val value: Coins,
        val fiat: Coins,
    )

    suspend fun loadTokens(testnet: Boolean, tokenRepository: TokenRepository): List<TokenEntity> {
        val jettonsAddress = jettons.map {
            it.jetton.address.toRawAddress()
        }

        return tokenRepository.getTokens(testnet, jettonsAddress)
    }

}
