package com.tonapps.tonkeeper.usecase.emulation

import com.tonapps.blockchain.ton.AndroidSecureRandom
import com.tonapps.icu.Coins
import com.tonapps.icu.Coins.Companion.sumOf
import com.tonapps.wallet.api.API
import com.tonapps.wallet.data.account.entities.MessageBodyEntity
import com.tonapps.wallet.data.account.entities.WalletEntity
import com.tonapps.wallet.data.core.entity.TransferType
import com.tonapps.wallet.data.settings.SettingsRepository
import org.ton.api.pk.PrivateKeyEd25519
import org.ton.cell.Cell
import org.ton.contract.wallet.WalletTransfer

/**
 * Native TOS transfer preview.
 *
 * The product only supports the native TOS balance and native transfers, so previewing a send
 * must never call the legacy event-emulation, fiat-rate, jetton, or Battery backends. The signed
 * message is evaluated with chain configuration plus account state read directly from the node.
 */
class EmulationUseCase(
    private val settingsRepository: SettingsRepository,
    private val api: API,
) {

    private val contractExecution = EmulationContractExecution(api)

    suspend operator fun invoke(
        message: MessageBodyEntity,
        useBattery: Boolean = false,
        forceRelayer: Boolean = false,
        checkTonBalance: Boolean = false,
        params: Boolean = false,
    ): Emulated = preview(message, checkTonBalance)

    suspend operator fun invoke(
        wallet: WalletEntity,
        seqNo: Int,
        unsignedBody: Cell,
        outMsgs: List<Cell>,
        forwardAmount: Coins,
    ): Emulated = try {
        val signedBoc = wallet.sign(
            privateKey = PrivateKeyEd25519(AndroidSecureRandom),
            seqNo = seqNo,
            body = unsignedBody,
        )
        val fee = contractExecution.computeRemoveExtensionFee(wallet, signedBoc, outMsgs)
        ensureBalance(wallet, fee + forwardAmount)
        result(fee)
    } catch (error: Throwable) {
        failure(error)
    }

    private suspend fun preview(
        message: MessageBodyEntity,
        checkBalance: Boolean,
    ): Emulated = try {
        val boc = message.createSignedBody(
            privateKey = PrivateKeyEd25519(AndroidSecureRandom),
            internalMessage = false,
        )
        val fee = contractExecution.computeFeeTos(message.wallet, boc, message.getOutMsgs())
        if (checkBalance) {
            val transferAmount = message.transfers.sumOf {
                Coins.of(it.coins.coins.toString())
            }
            ensureBalance(message.wallet, fee + transferAmount)
        }
        result(fee)
    } catch (error: Throwable) {
        failure(error)
    }

    private fun ensureBalance(wallet: WalletEntity, required: Coins) {
        val state = api.tos.getAccountState(wallet.accountId, wallet.testnet)
        val balance = Coins.ofNano(state.balance.toString())
        if (required > balance) {
            throw InsufficientBalanceError(balance, required)
        }
    }

    private fun result(fee: Coins) = Emulated(
        consequences = null,
        type = TransferType.Default,
        total = Emulated.Total(Coins.ZERO, 0, false),
        extra = Emulated.Extra(isRefund = false, value = fee, fiat = Coins.ZERO),
        currency = settingsRepository.currency,
    )

    private fun failure(error: Throwable) = Emulated(
        consequences = null,
        total = Emulated.Total(Coins.ZERO, 0, false),
        extra = Emulated.defaultExtra,
        currency = settingsRepository.currency,
        failed = true,
        type = TransferType.Default,
        error = error,
    )

    companion object {
        fun calculateTransferAmount(transfers: List<WalletTransfer>): Coins = transfers.sumOf {
            Coins.of(it.coins.coins.amount.toLong())
        }
    }
}
