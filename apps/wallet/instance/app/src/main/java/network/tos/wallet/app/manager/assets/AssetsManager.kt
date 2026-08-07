package network.tos.wallet.app.manager.assets

import android.content.Context
import network.tos.blockchain.ton.extensions.equalsAddress
import network.tos.icu.Coins
import network.tos.icu.Coins.Companion.sumOf
import network.tos.wallet.app.core.entities.AssetsEntity
import network.tos.wallet.app.core.entities.AssetsEntity.Companion.sort
import network.tos.wallet.app.core.entities.StakedEntity
import network.tos.wallet.app.extensions.isSafeModeEnabled
import network.tos.wallet.api.API
import network.tos.wallet.api.entity.TokenEntity
import network.tos.wallet.data.account.AccountRepository
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.core.currency.WalletCurrency
import network.tos.wallet.data.rates.RatesRepository
import network.tos.wallet.data.settings.SettingsRepository
import network.tos.wallet.data.staking.StakingPool
import network.tos.wallet.data.staking.StakingRepository
import network.tos.wallet.data.staking.entities.StakingEntity
import network.tos.wallet.data.token.TokenRepository
import network.tos.wallet.data.token.entities.AccountTokenEntity
import network.tos.wallet.data.token.entities.TokenRateEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

class AssetsManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val ratesRepository: RatesRepository,
    private val tokenRepository: TokenRepository,
    private val stakingRepository: StakingRepository,
    private val settingsRepository: SettingsRepository,
    private val accountRepository: AccountRepository,
    private val api: API,
) {

    private val cache = TotalBalanceCache(context)

    init {
        settingsRepository.tokenPrefsChangedFlow.drop(1).onEach {
            accountRepository.getSelectedWallet()?.let {
                cache.clear(it, settingsRepository.currency)
            }
        }.launchIn(scope)
    }

    suspend fun getAssets(
        wallet: WalletEntity,
        currency: WalletCurrency = settingsRepository.currency,
        refresh: Boolean,
    ): List<AssetsEntity>? {
        val tokens = getTokens(wallet, currency, refresh)
        val list = tokens.filter { it.token.isTon }
        if (list.isEmpty()) {
            return null
        }
        return list
    }

    suspend fun getTONBalance(
        wallet: WalletEntity, currency: WalletCurrency = settingsRepository.currency
    ) = getToken(
        wallet = wallet, token = "TON", currency = currency
    )?.balance ?: Coins.ZERO

    suspend fun getToken(
        wallet: WalletEntity, token: String, currency: WalletCurrency = settingsRepository.currency
    ): AssetsEntity.Token? {
        val tokens = getTokens(wallet, currency, false)
        return tokens.firstOrNull {
            it.token.address.equalsAddress(token)
        }
    }

    suspend fun getTokens(
        wallet: WalletEntity,
        accountIds: List<String>,
        currency: WalletCurrency = settingsRepository.currency
    ): List<AssetsEntity.Token> = withContext(Dispatchers.IO) {
        if (accountIds.isEmpty()) {
            emptyList()
        } else {
            val tokens = getTokens(wallet, currency, false)
            tokens.filter {
                accountIds.any { accountId -> it.token.address.equalsAddress(accountId) }
            }
        }
    }

    suspend fun getTokens(
        wallet: WalletEntity,
        currency: WalletCurrency = settingsRepository.currency,
        refresh: Boolean,
    ): List<AssetsEntity.Token> {
        val safeMode = settingsRepository.isSafeModeEnabled(api)
        val tokens =
            tokenRepository.get(currency, wallet.accountId, wallet.testnet, refresh)
                ?: return emptyList()
        tokens.firstOrNull()?.let {
            if (wallet.initialized != it.balance.initializedAccount) {
                accountRepository.setInitialized(wallet.id, it.balance.initializedAccount)
            }
        }
        return if (safeMode) {
            tokens.filter { it.verified }.map { AssetsEntity.Token(it) }
        } else {
            tokens.map { AssetsEntity.Token(it) }
        }
    }

    private suspend fun getStaked(
        wallet: WalletEntity,
        tokens: List<AccountTokenEntity>,
        currency: WalletCurrency = settingsRepository.currency,
        refresh: Boolean,
    ): List<AssetsEntity.Staked> {
        val staking = getStaking(wallet, refresh)
        val staked = StakedEntity.create(wallet, staking, tokens, currency, ratesRepository, api)
        return staked.map { AssetsEntity.Staked(it) }
    }

    private suspend fun getStaking(
        wallet: WalletEntity, refresh: Boolean
    ): StakingEntity {
        return stakingRepository.get(
            accountId = wallet.accountId, testnet = wallet.testnet, ignoreCache = refresh
        )
    }

    fun getCachedTotalBalance(
        wallet: WalletEntity,
        currency: WalletCurrency,
        sorted: Boolean = false,
    ) = cache.get(wallet, currency, sorted)

    suspend fun requestTotalBalance(
        wallet: WalletEntity,
        currency: WalletCurrency,
        refresh: Boolean = false,
        sorted: Boolean = false,
    ): Coins? {
        val totalBalance = calculateTotalBalance(wallet, currency, refresh, sorted) ?: return null
        cache.set(wallet, currency, sorted, totalBalance)
        return totalBalance
    }

    fun setCachedTotalBalance(
        wallet: WalletEntity, currency: WalletCurrency, sorted: Boolean = false, value: Coins
    ) {
        cache.set(wallet, currency, sorted, value)
    }

    suspend fun getTotalBalance(
        wallet: WalletEntity, currency: WalletCurrency, sorted: Boolean = false
    ) = getCachedTotalBalance(wallet, currency, sorted) ?: requestTotalBalance(
        wallet, currency, sorted
    )

    private suspend fun calculateTotalBalance(
        wallet: WalletEntity,
        currency: WalletCurrency,
        refresh: Boolean,
        sorted: Boolean,
    ): Coins? {
        var assets = getAssets(wallet, currency, refresh) ?: return null
        if (sorted) {
            assets = assets.sort(wallet, settingsRepository)
        }
        return assets.map { it.fiat }.sumOf { it }
    }

}
