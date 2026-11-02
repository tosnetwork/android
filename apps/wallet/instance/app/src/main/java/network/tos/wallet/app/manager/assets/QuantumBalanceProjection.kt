package network.tos.wallet.app.manager.assets

import network.tos.icu.Coins
import network.tos.wallet.api.entity.BalanceEntity
import network.tos.wallet.api.entity.TokenEntity
import network.tos.wallet.data.account.pq.QuantumSelfPayAccount
import network.tos.wallet.data.core.currency.WalletCurrency
import network.tos.wallet.data.token.entities.AccountTokenEntity
import network.tos.wallet.data.token.entities.TokenRateEntity
import java.math.BigDecimal

/** Recorded native balance, not a spendable amount or a fee estimate. */
object QuantumBalanceProjection {
    /** Metadata does not confer issuer verification or a fiat valuation. */
    fun token(value: network.tos.wallet.data.account.pq.QuantumTokenAssets.Token): AccountTokenEntity {
        val decimals = requireNotNull(value.decimals) { "Token precision is unresolved" }
        val metadata = TokenEntity(
            blockchain = network.tos.wallet.api.entity.value.Blockchain.TON,
            address = value.master,
            name = value.name ?: "Token",
            symbol = value.symbol ?: "Token",
            imageUri = android.net.Uri.EMPTY,
            decimals = decimals,
            verification = TokenEntity.Verification.none,
            isRequestMinting = false,
            isTransferable = false,
            customPayloadApiUri = null,
        )
        return AccountTokenEntity(BalanceEntity(metadata,
            Coins.of(BigDecimal(value.units).movePointLeft(decimals), decimals), value.wallet,
            initializedAccount = true, isRequestMinting = false, isTransferable = false))
    }

    fun token(accountId: String, snapshot: QuantumSelfPayAccount.Snapshot, currency: WalletCurrency): AccountTokenEntity {
        val amount = Coins.of(BigDecimal(snapshot.balance).movePointLeft(9), 9)
        return AccountTokenEntity(
            BalanceEntity(TokenEntity.TON, amount, accountId,
                initializedAccount = snapshot.status is QuantumSelfPayAccount.Status.Active ||
                    snapshot.status is QuantumSelfPayAccount.Status.Frozen),
            if (currency == WalletCurrency.TON) TokenRateEntity(currency, amount, Coins.ONE, "") else null
        )
    }
}
