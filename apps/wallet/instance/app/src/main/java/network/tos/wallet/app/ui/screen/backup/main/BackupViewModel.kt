package network.tos.wallet.app.ui.screen.backup.main

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import network.tos.extensions.filterList
import network.tos.icu.Coins
import network.tos.icu.Coins.Companion.sumOf
import network.tos.icu.CurrencyFormatter
import network.tos.wallet.app.core.BalanceType
import network.tos.wallet.app.core.entities.AssetsEntity
import network.tos.wallet.app.core.entities.StakedEntity
import network.tos.wallet.app.ui.base.BaseWalletVM
import network.tos.wallet.app.ui.screen.backup.main.list.Item
import network.tos.uikit.list.ListCell
import network.tos.wallet.api.API
import network.tos.wallet.api.entity.TokenEntity
import network.tos.wallet.data.account.AccountRepository
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.backup.BackupRepository
import network.tos.wallet.data.passcode.PasscodeManager
import network.tos.wallet.data.rates.RatesRepository
import network.tos.wallet.data.settings.SettingsRepository
import network.tos.wallet.data.staking.StakingRepository
import network.tos.wallet.data.token.TokenRepository
import network.tos.wallet.localization.Localization
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BackupViewModel(
    app: Application,
    private val wallet: WalletEntity,
    private val accountRepository: AccountRepository,
    private val backupRepository: BackupRepository,
    private val passcodeManager: PasscodeManager,
    private val settingsRepository: SettingsRepository,
    private val ratesRepository: RatesRepository,
    private val stakingRepository: StakingRepository,
    private val tokenRepository: TokenRepository,
    private val api: API
): BaseWalletVM(app) {

    val uiItemsFlow = backupRepository.stream.filterList { backup ->
        backup.walletId == wallet.id
    }.map { backups ->
        val backupsCount = backups.size
        val balanceFiat = if (backupsCount > 0) Coins.ZERO else getTotalBalanceFiat(wallet)
        val balanceType = getBalanceType(balanceFiat)

        val items = mutableListOf<Item>()
        if (balanceType != BalanceType.Zero) {
            val format = CurrencyFormatter.formatFiat(settingsRepository.currency.code, balanceFiat)
            items.add(Item.Alert(format, balanceType == BalanceType.Huge))
            items.add(Item.Space)
        }
        items.add(Item.Header)
        items.add(Item.Space)

        for ((index, backup) in backups.withIndex()) {
            val position = ListCell.getPosition(backupsCount, index)
            items.add(Item.Backup(position, backup, settingsRepository.getLocale()))
        }
        if (backupsCount > 0) {
            items.add(Item.Space)
            items.add(Item.RecoveryPhrase)
        } else if (balanceType == BalanceType.Zero) {
            items.add(Item.ManualBackup)
        } else {
            items.add(Item.ManualAccentBackup)
        }
        items.toList()
    }.flowOn(Dispatchers.IO)

    fun getRecoveryPhrase(
        context: Context,
        callback: (Array<String>, Throwable?) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val confirmed = passcodeManager.confirmation(context, getString(Localization.app_name))
            if (confirmed) {
                try {
                    val words = accountRepository.getMnemonic(wallet.id) ?: emptyArray()
                    if (words.isEmpty()) {
                        val hasPrivateKey = accountRepository.getPrivateKey(wallet.id) != null
                        if (hasPrivateKey) {
                            throw IllegalStateException("No mnemonic but has private key")
                        } else {
                            throw IllegalStateException("No mnemonic and no private key")
                        }
                    }
                    withContext(Dispatchers.Main) {
                        callback(words, null)
                    }
                } catch (e: Throwable) {
                    withContext(Dispatchers.Main) {
                        callback(emptyArray(), e)
                    }
                }
            }
        }
    }

    private suspend fun getBalanceType(
        balanceFiat: Coins,
    ): Int {
        val rates = ratesRepository.getTONRates(settingsRepository.currency)
        val balanceTON = rates.convertFromFiat(TokenEntity.TON.address, balanceFiat)
        return BalanceType.getBalanceType(balanceTON)
    }

    private suspend fun getTotalBalanceFiat(
        wallet: WalletEntity
    ): Coins {
        val assets = getAssets(wallet)
        return if (wallet.testnet) {
            assets.first().fiat
        } else {
            assets.map { it.fiat }.sumOf { it }
        }
    }

    private suspend fun getAssets(
        wallet: WalletEntity,
    ): List<AssetsEntity> {
        val currency = settingsRepository.currency
        val tokens = tokenRepository.get(currency, wallet.accountId, wallet.testnet) ?: emptyList()
        val staking = stakingRepository.get(wallet.accountId, wallet.testnet)
        val staked = StakedEntity.create(wallet, staking, tokens, currency, ratesRepository, api)
        val liquid = staked.find { it.isTonstakers }?.liquidToken
        val filteredTokens = if (liquid == null) tokens else tokens.filter { !liquid.token.address.contains(it.address)  }
        return (filteredTokens.map { AssetsEntity.Token(it) } + staked.map {
            AssetsEntity.Staked(it)
        }).sortedBy { it.fiat }.reversed()
    }
}