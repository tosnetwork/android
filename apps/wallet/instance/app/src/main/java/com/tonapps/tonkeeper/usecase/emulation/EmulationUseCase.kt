package com.tonapps.tonkeeper.usecase.emulation

import com.tonapps.blockchain.ton.AndroidSecureRandom
import com.tonapps.icu.Coins
import com.tonapps.icu.Coins.Companion.sumOf
import com.tonapps.tonkeeper.extensions.isSafeModeEnabled
import com.tonapps.tonkeeper.manager.assets.AssetsManager
import com.tonapps.wallet.api.API
import com.tonapps.wallet.api.entity.BalanceEntity
import com.tonapps.wallet.api.entity.TokenEntity
import com.tonapps.wallet.data.account.AccountRepository
import com.tonapps.wallet.data.account.entities.MessageBodyEntity
import com.tonapps.wallet.data.account.entities.WalletEntity
import com.tonapps.wallet.data.battery.BatteryRepository
import com.tonapps.wallet.data.core.entity.TransferType
import com.tonapps.wallet.data.core.currency.WalletCurrency
import com.tonapps.wallet.data.rates.RatesRepository
import com.tonapps.wallet.data.settings.SettingsRepository
import com.tonapps.wallet.data.token.TokenRepository
import io.tonapi.models.JettonQuantity
import io.tonapi.models.MessageConsequences
import io.tonapi.models.Risk
import org.ton.api.pk.PrivateKeyEd25519
import org.ton.cell.Cell
import org.ton.contract.wallet.WalletTransfer
import java.math.BigDecimal
import kotlin.math.abs

class EmulationUseCase(
    private val ratesRepository: RatesRepository,
    private val settingsRepository: SettingsRepository,
    private val accountRepository: AccountRepository,
    private val batteryRepository: BatteryRepository,
    private val api: API,
    private val assetsManager: AssetsManager,
    private val tokenRepository: TokenRepository,
) {

    private val contractExecution = EmulationContractExecution(api)

    suspend operator fun invoke(
        message: MessageBodyEntity,
        useBattery: Boolean = false,
        forceRelayer: Boolean = false,
        checkTonBalance: Boolean = false,
        params: Boolean = false,
    ): Emulated {
        return try {
            if (forceRelayer || useBattery) {
                emulateWithBattery(
                    message = message,
                    forceRelayer = forceRelayer,
                )
            } else {
                emulate(message, params, checkTonBalance)
            }
        } catch (e: Throwable) {
            Emulated(
                consequences = null,
                total = Emulated.Total(Coins.ZERO, 0, false),
                extra = Emulated.defaultExtra,
                currency = settingsRepository.currency,
                failed = true,
                type = TransferType.Default,
                error = e,
            )
        }
    }

    suspend operator fun invoke(
        wallet: WalletEntity,
        seqNo: Int,
        unsignedBody: Cell,
        outMsgs: List<Cell>,
        forwardAmount: Coins,
    ): Emulated {
        return try {
            emulate(wallet, seqNo, unsignedBody, outMsgs, forwardAmount)
        } catch (e: Throwable) {
            Emulated(
                consequences = null,
                total = Emulated.Total(Coins.ZERO, 0, false),
                extra = Emulated.defaultExtra,
                currency = settingsRepository.currency,
                failed = true,
                type = TransferType.Default,
                error = e,
            )
        }
    }

    private fun createMessage(
        message: MessageBodyEntity,
        internalMessage: Boolean
    ): Cell {
        return message.createSignedBody(
            privateKey = PrivateKeyEd25519(AndroidSecureRandom),
            internalMessage = internalMessage
        )
    }

    private suspend fun emulateWithBattery(
        message: MessageBodyEntity,
        forceRelayer: Boolean,
    ): Emulated {
        if (api.config.batterySendDisabled) {
            throw IllegalStateException("Battery is disabled")
        }

        val wallet = message.wallet
        val tonProofToken = accountRepository.requestTonProofToken(wallet)
            ?: throw IllegalStateException("Can't find TonProof token")
        val boc = createMessage(message, true)

        val (consequences, withBattery) = batteryRepository.emulate(
            tonProofToken = tonProofToken,
            publicKey = wallet.publicKey,
            testnet = wallet.testnet,
            boc = boc,
            forceRelayer = forceRelayer,
            safeModeEnabled = settingsRepository.isSafeModeEnabled(api)
        ) ?: throw IllegalStateException("Failed to emulate battery")

        return parseEmulated(wallet, consequences, TransferType.Battery)
    }

    private suspend fun emulate(
        wallet: WalletEntity,
        seqNo: Int,
        unsignedBody: Cell,
        outMsgs: List<Cell>,
        forwardAmount: Coins,
    ): Emulated {
        val signedBoc = wallet.sign(
            privateKey = PrivateKeyEd25519(AndroidSecureRandom),
            seqNo = seqNo,
            body = unsignedBody
        )

        val account = api.accounts(wallet.testnet).getAccount(wallet.accountId)
        val accountBalance = Coins.of(account.balance)
        val totalFee = contractExecution.computeRemoveExtensionFee(wallet, signedBoc, outMsgs)
        val totalAmount =
            totalFee + forwardAmount
        if (totalAmount > accountBalance) {
            throw InsufficientBalanceError(accountBalance, totalAmount)
        }

        val consequences = api.emulate(
            cell = signedBoc,
            testnet = wallet.testnet,
            safeModeEnabled = settingsRepository.isSafeModeEnabled(api)
        ) ?: throw IllegalArgumentException("Emulation failed")
        return parseEmulated(wallet, consequences, TransferType.Default)
    }

    private suspend fun emulate(
        message: MessageBodyEntity,
        params: Boolean,
        checkTonBalance: Boolean
    ): Emulated {
        val wallet = message.wallet
        val boc = createMessage(message, false)

        if (checkTonBalance) {
            val account = api.accounts(wallet.testnet).getAccount(wallet.accountId)
            val accountBalance = Coins.of(account.balance)
            val totalFee = contractExecution.computeFee(wallet, account, boc, message.getOutMsgs())
            val totalAmount =
                totalFee + message.transfers.sumOf { Coins.of(it.coins.coins.toString()) }
            if (totalAmount > accountBalance) {
                throw InsufficientBalanceError(accountBalance, totalAmount)
            }
        }

        val consequences = if (params) {
            api.emulate(
                cell = boc,
                testnet = wallet.testnet,
                address = wallet.address,
                balance = ((Coins.ONE + Coins.ONE) + calculateTransferAmount(message.transfers)).toLong(),
                safeModeEnabled = settingsRepository.isSafeModeEnabled(api)
            )
        } else {
            api.emulate(
                cell = boc,
                testnet = wallet.testnet,
                safeModeEnabled = settingsRepository.isSafeModeEnabled(api)
            )
        }
        // TOS: a bare node has no event-level emulation; fall back to a local fee-only preview.
        return if (consequences != null) {
            parseEmulated(wallet, consequences, TransferType.Default)
        } else {
            localFeeEmulated(wallet, boc, message)
        }
    }

    /**
     * TOS fee-only preview: when the bare node has no event-level emulation, show a real fee
     * computed locally (TosSource state + standard config) instead of a placeholder.
     */
    private suspend fun localFeeEmulated(
        wallet: WalletEntity,
        boc: Cell,
        message: MessageBodyEntity,
        currency: WalletCurrency = settingsRepository.currency,
    ): Emulated {
        val fee = contractExecution.computeFeeTos(wallet, boc, message.getOutMsgs())
        val rates = ratesRepository.getTONRates(currency)
        val fiat = rates.convertTON(fee)
        return Emulated(
            consequences = null,
            type = TransferType.Default,
            total = Emulated.Total(Coins.ZERO, 0, false),
            extra = Emulated.Extra(isRefund = false, value = fee, fiat = fiat),
            currency = currency,
        )
    }

    private suspend fun parseEmulated(
        wallet: WalletEntity,
        consequences: MessageConsequences,
        transferType: TransferType,
        currency: WalletCurrency = settingsRepository.currency,
    ): Emulated {
        val total = getTotal(wallet, consequences.risk, currency)
        val extra = getExtra(consequences.event.extra, currency)
        return Emulated(
            consequences = consequences,
            type = transferType,
            total = total,
            extra = extra,
            currency = currency,
        )
    }

    private suspend fun getTotal(
        wallet: WalletEntity,
        risk: Risk,
        currency: WalletCurrency,
    ): Emulated.Total {
        val balanceFiat = assetsManager.getTotalBalance(wallet, currency) ?: Coins.ZERO
        val ton = tokenRepository.getTON(currency, wallet.accountId, wallet.testnet, true)
        val tonValue = if (risk.transferAllRemainingBalance) {
            ton?.balance?.value?.toLong() ?: risk.ton
        } else {
            risk.ton
        }
        val tokens = getTokens(wallet, tonValue, risk.jettons)
        val rates = ratesRepository.getRates(currency, tokens.map { it.token.address })
        val totalFiat = tokens.map { token ->
            rates.convert(token.token.address, token.value)
        }.sumOf { it }

        val diff = if (balanceFiat > Coins.ZERO) {
            totalFiat.value / balanceFiat.value
        } else {
            totalFiat.value
        }

        return Emulated.Total(
            totalFiat = totalFiat,
            nftCount = risk.nfts.size,
            isDangerous = diff >= BigDecimal("0.2")
        )
    }

    private suspend fun getExtra(
        extra: Long,
        currency: WalletCurrency,
    ): Emulated.Extra {
        val value = Coins.of(abs(extra))
        val rates = ratesRepository.getTONRates(currency)
        val fiat = rates.convertTON(value)

        return Emulated.Extra(
            isRefund = extra >= 0,
            value = value,
            fiat = fiat,
        )
    }

    private fun getTokens(
        wallet: WalletEntity,
        tonValue: Long,
        jettons: List<JettonQuantity>
    ): List<BalanceEntity> {
        val list = mutableListOf<BalanceEntity>()
        list.add(
            BalanceEntity.create(
                accountId = wallet.address,
                value = Coins.of(tonValue),
            )
        )
        for (jettonQuantity in jettons) {
            val token = TokenEntity(jettonQuantity.jetton)
            val value = Coins.ofNano(jettonQuantity.quantity, token.decimals)
            list.add(
                BalanceEntity(
                    token = token,
                    value = value,
                    walletAddress = jettonQuantity.walletAddress.address
                )
            )
        }
        return list.toList()
    }

    companion object {

        fun calculateTransferAmount(transfers: List<WalletTransfer>): Coins {
            return transfers.sumOf {
                Coins.of(it.coins.coins.amount.toLong())
            }
        }
    }
}
