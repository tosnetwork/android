package network.tos.wallet.data.rates.source

import android.content.Context
import network.tos.extensions.toByteArray
import network.tos.extensions.toParcel
import network.tos.wallet.data.core.BlobDataSource
import network.tos.wallet.data.core.currency.WalletCurrency
import network.tos.wallet.data.rates.entity.RateEntity
import network.tos.wallet.data.rates.entity.RatesEntity
import java.util.concurrent.TimeUnit

internal class BlobDataSource(context: Context): BlobDataSource<RatesEntity>(
    context = context,
    path = "rates",
    timeout = TimeUnit.HOURS.toMillis(12)
) {

    override fun onUnmarshall(bytes: ByteArray) = bytes.toParcel<RatesEntity>()

    override fun onMarshall(data: RatesEntity) = data.toByteArray()

    fun get(currency: WalletCurrency): RatesEntity {
        val rates = getCache(currency.code) ?: RatesEntity.empty(currency)
        if (rates.isEmpty) {
            clearCache(currency.code)
            return rates
        }
        return rates.copy()
    }

    fun add(currency: WalletCurrency, list: List<RateEntity>) {
        if (list.isEmpty()) {
            return
        }
        val rates = get(currency).merge(list)
        setCache(currency.code, rates)
    }

}