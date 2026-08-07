package network.tos.wallet.app.ui.screen.events.compose.history.paging

import android.util.Log
import network.tos.wallet.app.ui.screen.events.compose.history.state.TxTronParams
import network.tos.wallet.data.account.AccountRepository
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.settings.SettingsRepository
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

internal class TxTronParamsProvider(
    private val wallet: WalletEntity,
    private val accountRepository: AccountRepository,
    private val settingsRepository: SettingsRepository,
) {

    private val tronEnabled: Boolean
        get() = settingsRepository.getTronUsdtEnabled(wallet.id)

    private val isTronSupported: Boolean
        get() = tronEnabled && wallet.hasPrivateKey && !wallet.testnet

    @OptIn(ExperimentalAtomicApi::class)
    private val atomicRef = AtomicReference<TxTronParams?>(null)

    @OptIn(ExperimentalAtomicApi::class)
    suspend fun get(): TxTronParams? {
        if (!isTronSupported) {
            return null
        }
        return atomicRef.load() ?: create()
    }

    @OptIn(ExperimentalAtomicApi::class)
    private suspend fun create(): TxTronParams? {
        val params = TxTronParams(
            address = accountRepository.getTronBlockchainAddress(wallet.id),
            tonProofToken = accountRepository.requestTonProofToken(wallet),
        )
        if (params.isEmtpy) {
            atomicRef.store(null)
            return null
        }
        atomicRef.store(params)
        return params
    }

}