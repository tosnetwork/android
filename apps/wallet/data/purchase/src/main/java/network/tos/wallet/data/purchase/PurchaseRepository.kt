package network.tos.wallet.data.purchase

import android.content.Context
import android.util.Log
import network.tos.extensions.getParcelable
import network.tos.extensions.prefs
import network.tos.extensions.putParcelable
import network.tos.extensions.toByteArray
import network.tos.extensions.toParcel
import network.tos.wallet.api.API
import network.tos.wallet.data.core.BlobDataSource
import network.tos.wallet.data.core.currency.WalletCurrency
import network.tos.wallet.data.purchase.entity.MerchantEntity
import network.tos.wallet.data.purchase.entity.OnRamp
import network.tos.wallet.data.purchase.entity.PurchaseCategoryEntity
import network.tos.wallet.data.purchase.entity.PurchaseDataEntity
import network.tos.wallet.data.purchase.entity.PurchaseMethodEntity
import io.Serializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.ton.crypto.digest.sha512
import org.ton.crypto.hex
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

class PurchaseRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    private val api: API
) : BlobDataSource<PurchaseDataEntity>(
    context = context,
    path = "purchase",
    timeout = TimeUnit.DAYS.toMillis(1)
) {

    private val onRampCache = simple<OnRamp.Data>(context, "onRamp", TimeUnit.DAYS.toMillis(14))
    private val merchantsCache = simpleJSON<List<MerchantEntity>>(context,"merchants", TimeUnit.DAYS.toMillis(1))

    fun onRampDataFlow() = flow {
        getOnRampDataCache()?.let {
            emit(it)
        }

        fetchOnRampDataCache()?.let {
            emit(it)
        }
    }.flowOn(Dispatchers.IO)

    private fun loadOnRampMerchants(): List<MerchantEntity> {
        return try {
            val data = api.getOnRampMerchants() ?: throw Exception("No merchants found")
            Serializer.JSON.decodeFromString<List<MerchantEntity>>(data)
        } catch (e: Throwable) {
            emptyList()
        }
    }

    fun getMerchants(): List<MerchantEntity> {
        var list = merchantsCache.getCache("main") ?: emptyList()
        if (list.isEmpty()) {
            list = loadOnRampMerchants()
            merchantsCache.setCache("main", list)
        }
        return list
    }

    suspend fun getPaymentMethods(currency: String): List<OnRamp.PaymentMethodMerchant> = withContext(Dispatchers.IO) {
        try {
            val data = api.getOnRampPaymentMethods(currency) ?: throw Exception("No payment methods found for country: ${api.country}")
            Log.d("PurchaseRepositoryLog", "getPaymentMethods: $data")
            Serializer.JSON.decodeFromString<List<OnRamp.PaymentMethodMerchant>>(data)
        } catch (e: Throwable) {
            Log.e("PurchaseRepositoryLog", "error", e)
            emptyList()
        }
    }

    private suspend fun loadOnRampData(): OnRamp.Data? = withContext(Dispatchers.IO) {
        val data = api.getOnRampData() ?: return@withContext null
        try {
            Serializer.fromJSON<OnRamp.Data>(data)
        } catch (e: Throwable) {
            null
        }
    }

    private fun getOnRampDataCache(): OnRamp.Data? {
        val cacheKey = "data_${api.country}"
        return onRampCache.getCache(cacheKey)
    }

    private suspend fun fetchOnRampDataCache(): OnRamp.Data? {
        val data = loadOnRampData() ?: return null
        val cacheKey = "data_${api.country}"
        onRampCache.setCache(cacheKey, data)
        return data
    }


    fun get(
        testnet: Boolean,
        country: String,
        locale: Locale,
    ): Pair<List<PurchaseCategoryEntity>, List<PurchaseCategoryEntity>>? {
        val data = get(testnet, locale) ?: return null
        val methods = data.getCountry(country).methods
        return filterMethods(data.buy, methods) to filterMethods(data.sell, methods)
    }

    private fun filterMethods(
        categories: List<PurchaseCategoryEntity>,
        methods: List<String>
    ): List<PurchaseCategoryEntity> {
        val list = mutableListOf<PurchaseCategoryEntity>()
        for (category in categories) {
            if (category.type == "swap") {
                list.add(category.copy(items = category.items))
            } else {
                val items = category.items.filter {
                    methods.contains(it.id)
                }
                if (items.isNotEmpty()) {
                    // Sort by methods
                    val sortedItems = items.sortedBy {
                        methods.indexOf(it.id)
                    }
                    val categoryEntity = category.copy(
                        items = sortedItems
                    )
                    list.add(categoryEntity)
                }
            }
        }
        return list
    }

    fun getMethod(id: String, testnet: Boolean, locale: Locale): PurchaseMethodEntity? {
        val data = get(testnet, locale) ?: return null
        val methods = (data.buy + data.sell).map { it.items }.flatten()
        return methods.find { it.id == id }
    }

    private fun get(testnet: Boolean, locale: Locale): PurchaseDataEntity? {
        val key = cacheKey(testnet, locale)
        var data = getCache(key)
        if (data == null) {
            data = load(testnet, locale) ?: return null
            setCache(key, data)
        }
        return data
    }

    private fun load(testnet: Boolean, locale: Locale): PurchaseDataEntity? {
        val json = api.getFiatMethods(testnet, locale) ?: return null
        return PurchaseDataEntity(json)
    }

    private fun cacheKey(testnet: Boolean, locale: Locale): String {
        val prefix = if (testnet) "testnet" else "mainnet"
        return "$prefix-${locale.language}"
    }

    fun replaceUrl(
        url: String,
        address: String,
        currency: String
    ): String {
        var replacedUrl = url.replace("{ADDRESS}", address)
        replacedUrl = replacedUrl.replace("{CUR_FROM}", currency)
        replacedUrl = replacedUrl.replace("{CUR_TO}", "TON")

        if (replacedUrl.contains("TX_ID")) {
            val mercuryoSecret = api.config.mercuryoSecret
            val signature = hex(sha512((address+mercuryoSecret).toByteArray()))
            val tx = "mercuryo_" + UUID.randomUUID().toString()
            replacedUrl = replacedUrl.replace("{TX_ID}", tx)
            replacedUrl = replacedUrl.replace("=TON&", "=TONCOIN&")
            replacedUrl += "&signature=$signature"
        }
        return replacedUrl
    }

    override fun onMarshall(data: PurchaseDataEntity) = data.toByteArray()

    override fun onUnmarshall(bytes: ByteArray) = bytes.toParcel<PurchaseDataEntity>()
}