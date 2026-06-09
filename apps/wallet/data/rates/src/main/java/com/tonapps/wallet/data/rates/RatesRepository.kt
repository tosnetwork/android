package com.tonapps.wallet.data.rates

import android.content.Context
import android.util.Log
import com.google.firebase.annotations.concurrent.Background
import com.tonapps.icu.Coins
import com.tonapps.wallet.api.API
import com.tonapps.wallet.api.entity.TokenEntity
import com.tonapps.wallet.data.core.currency.WalletCurrency
import com.tonapps.wallet.data.rates.entity.RateDiffEntity
import com.tonapps.wallet.data.rates.entity.RateEntity
import com.tonapps.wallet.data.rates.entity.RatesEntity
import com.tonapps.wallet.data.rates.source.BlobDataSource
import io.tonapi.models.TokenRates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal

class RatesRepository(
    context: Context,
    private val api: API
) {

    private val localDataSource = BlobDataSource(context)
    private val manager = RateManager()

    suspend fun getRate(
        from: WalletCurrency,
        to: WalletCurrency,
        baseCurrency: WalletCurrency = WalletCurrency.USD
    ): RateData? = withContext(Dispatchers.IO) {
        if (!manager.hasRate(from, to)) {
            val all = listOf(from, to, baseCurrency)
            val currency = all.first { it.fiat }
            val tokens = all.filter { !it.fiat }
            val response = fetchRates(currency.code, tokens.map { it.tokenQuery })
            for (token in tokens) {
                val tokenRates = response[token.tokenQuery] ?: continue
                val price = tokenRates.prices?.get(currency.code)?.let {
                    Coins.of(it, currency.decimals)
                } ?: continue
                manager.addRate(token, currency, price)
            }
        }
        manager.getRate(from, to)?.let {
            RateData(from, to, it)
        }
    }

    suspend fun convert(
        amount: Coins,
        from: WalletCurrency,
        to: WalletCurrency
    ): Coins {
        getRate(from, to)
        return manager.convert(amount, from, to) ?: Coins.ZERO
    }

    suspend fun updateAll(currency: WalletCurrency, tokens: List<String>) = withContext(Dispatchers.IO) {
        load(currency, tokens.take(100).toMutableList())
    }

    suspend fun updateAll(currency: WalletCurrency) = withContext(Dispatchers.IO) {
        updateAll(currency, localDataSource.get(currency).tokens)
    }

    fun cache(currency: WalletCurrency, tokens: List<String>): RatesEntity {
        return localDataSource.get(currency).filter(tokens)
    }

    fun load(currency: WalletCurrency, token: String) {
        load(currency, mutableListOf(token))
    }

    fun load(currency: WalletCurrency, tokens: MutableList<String>) {
        if (!tokens.contains(TokenEntity.TON.address)) {
            tokens.add(TokenEntity.TON.address)
        }
        if (!tokens.contains(TokenEntity.USDT.address)) {
            tokens.add(TokenEntity.USDT.address)
        }
        val rates = mutableMapOf<String, TokenRates>()
        for (chunk in tokens.chunked(100)) {
            runCatching { fetchRates(currency.code, chunk) }.onSuccess(rates::putAll)
        }
        val usdtRate = rates[TokenEntity.USDT.address]
        usdtRate?.let {
            rates.put(TokenEntity.TRON_USDT.address, usdtRate)
        }
        insertRates(currency, rates)
    }

    private fun fetchRates(code: String, tokens: List<String>): Map<String, TokenRates> {
        if (tokens.size > 100) {
            throw IllegalArgumentException("Too many tokens requested: ${tokens.size}")
        }
        return api.getRates(code, tokens) ?: throw IllegalStateException("Failed to fetch rates for $code with tokens: $tokens")
    }

    fun insertRates(currency: WalletCurrency, rates: Map<String, TokenRates>) {
        if (rates.isEmpty()) {
            return
        }
        val entities = mutableListOf<RateEntity>()
        for (rate in rates) {
            val value = rate.value
            val prices = value.prices?.get(currency.code)?.let(::BigDecimal)
            val bigDecimal = prices ?: BigDecimal.ZERO

            entities.add(RateEntity(
                tokenCode = rate.key,
                currency = currency,
                value = Coins.of(bigDecimal, currency.decimals),
                diff = RateDiffEntity(currency, value),
            ))
        }
        localDataSource.add(currency, entities)
    }

    private fun getCachedRates(currency: WalletCurrency, tokens: List<String>): RatesEntity {
        return localDataSource.get(currency).filter(tokens)
    }

    suspend fun getRates(currency: WalletCurrency, token: String): RatesEntity {
        return getRates(currency, listOf(token))
    }

    suspend fun getTONRates(currency: WalletCurrency): RatesEntity {
        return getRates(currency, TokenEntity.TON.address)
    }

    suspend fun getRates(
        currency: WalletCurrency,
        tokens: List<String>
    ): RatesEntity = withContext(Dispatchers.IO) {
        val rates = getCachedRates(currency, tokens)
        if (rates.hasTokens(tokens)) {
            rates
        } else {
            load(currency, tokens.toMutableList())
            getCachedRates(currency, tokens)
        }
    }
}