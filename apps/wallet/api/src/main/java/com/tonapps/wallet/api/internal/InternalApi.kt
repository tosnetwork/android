package com.tonapps.wallet.api.internal

import android.content.Context
import android.util.Log
import androidx.collection.ArrayMap
import androidx.core.net.toUri
import com.tonapps.extensions.CrashReporter
import com.tonapps.extensions.isDebug
import com.tonapps.extensions.locale
import com.tonapps.extensions.map
import com.tonapps.network.get
import com.tonapps.network.postJSON
import com.tonapps.wallet.api.Constants
import com.tonapps.wallet.api.entity.ConfigEntity
import com.tonapps.wallet.api.entity.EthenaEntity
import com.tonapps.wallet.api.entity.NotificationEntity
import com.tonapps.wallet.api.entity.OnRampArgsEntity
import com.tonapps.wallet.api.entity.StoryEntity
import com.tonapps.wallet.api.withRetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.Locale

internal class InternalApi(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val appVersionName: String
) {

    private var _deviceCountry: String? = null
    private var _storeCountry: String? = null
    private var _apiEndpoint = Constants.TOS_API_MAINNET.toUri()

    val country: String
        get() = _storeCountry ?: _deviceCountry ?: Locale.getDefault().country.uppercase()

    fun setCountry(deviceCountry: String, storeCountry: String?) {
        _deviceCountry = deviceCountry.uppercase()
        _storeCountry = storeCountry?.uppercase()
    }

    fun setApiUrl(url: String) {
        _apiEndpoint = url.toUri()
    }

    private fun endpoint(
        path: String,
        testnet: Boolean,
        platform: String,
        build: String,
        boot: Boolean = false,
        queryParams: Map<String, String> = emptyMap(),
        bootFallback: Boolean = false,
    ): String = runBlocking {
        // TOS: no longer use boot/block.tos.network; route to the self-hosted config url or _apiEndpoint.
        val builder = if ((boot || bootFallback) && Constants.TOS_CONFIG_URL.isNotBlank()) {
            Constants.TOS_CONFIG_URL.toUri().buildUpon()
        } else {
            _apiEndpoint.buildUpon()
        }
        builder
            .appendEncodedPath(path)
            .appendQueryParameter("lang", context.locale.language)
            .appendQueryParameter("build", build)
            .appendQueryParameter("platform", platform)
            .appendQueryParameter("chainName", if (testnet) "testnet" else "mainnet")
            .appendQueryParameter("bundle_id", context.packageName)

        _storeCountry?.let {
            builder.appendQueryParameter("store_country_code", it)
        }
        _deviceCountry?.let {
            builder.appendQueryParameter("device_country_code", it)
        }

        queryParams.forEach {
            builder.appendQueryParameter(it.key, it.value)
        }

        builder.build().toString()
    }

    private fun request(
        path: String,
        testnet: Boolean,
        platform: String = "android",
        build: String = appVersionName,
        locale: Locale,
        boot: Boolean = false,
        queryParams: Map<String, String> = emptyMap(),
        bootFallback: Boolean = false,
    ): JSONObject {
        val url = endpoint(path, testnet, platform, build, boot, queryParams, bootFallback)
        val headers = ArrayMap<String, String>()
        headers["Accept-Language"] = locale.toString()
        val body = withRetry {
            okHttpClient.get(url, headers)
        } ?: throw IllegalStateException("Internal API request failed")
        return JSONObject(body)
    }

    private fun swapEndpoint(prefix: String, path: String): String {
        val builder = prefix.toUri().buildUpon()
            .appendEncodedPath(path)
        _deviceCountry?.let {
            builder.appendQueryParameter("device_country_code", _deviceCountry)
            builder.appendQueryParameter("country", _storeCountry ?: _deviceCountry)
        }
        _storeCountry?.let {
            builder.appendQueryParameter("store_country_code", _storeCountry)
        }
        return builder.build().toString()
    }

    fun getOnRampData(prefix: String) = withRetry {
        okHttpClient.get(swapEndpoint(prefix, "v2/onramp/currencies"))
    }

    fun getOnRampPaymentMethods(prefix: String, currency: String) = withRetry {
        okHttpClient.get(swapEndpoint(prefix, "v2/onramp/payment_methods"))
    }

    fun getOnRampMerchants(prefix: String) = withRetry {
        okHttpClient.get(swapEndpoint(prefix, "v2/onramp/merchants"))
    }

    fun calculateOnRamp(prefix: String, args: OnRampArgsEntity): String? {
        val json = args.toJSON()
        _deviceCountry?.let { json.put("country", _deviceCountry) }
        return withRetry {
            okHttpClient.postJSON(
                swapEndpoint(prefix, "v2/onramp/calculate"),
                json.toString()
            ).body.string()
        }
    }

    fun getNotifications(): List<NotificationEntity> {
        val json = request("notifications", false, locale = context.locale)
        val array = json.getJSONArray("notifications")
        val list = mutableListOf<NotificationEntity>()
        for (i in 0 until array.length()) {
            list.add(NotificationEntity(array.getJSONObject(i)))
        }
        return list.toList()
    }

    fun getScamDomains(): Array<String> {
        // TOS: do not depend on scam.tos.network; return empty when there is no external scam-domain source.
        return emptyArray()
    }

    fun getBrowserApps(testnet: Boolean, locale: Locale): JSONObject {
        val data = request("apps/popular", testnet, locale = locale)
        return data.getJSONObject("data")
    }

    fun getFiatMethods(testnet: Boolean = false, locale: Locale): JSONObject {
        val data = request("fiat/methods", testnet, locale = locale)
        return data.getJSONObject("data")
    }

    fun downloadConfig(testnet: Boolean, fallback: Boolean = false): ConfigEntity? {
        // TOS: when no self-hosted config endpoint is set, stop fetching from boot.tos.network
        // and use the built-in default config (ConfigEntity.default).
        if (Constants.TOS_CONFIG_URL.isBlank()) {
            return null
        }
        return try {
            val json = request(
                "keys",
                testnet,
                locale = context.locale,
                boot = true,
                bootFallback = fallback
            )
            ConfigEntity(json, context.isDebug)
        } catch (e: Throwable) {
            if (!fallback) {
                downloadConfig(testnet, true)
            } else {
                CrashReporter.recordException(e)
                null
            }
        }
    }

    fun getStories(id: String): StoryEntity.Stories? {
        return try {
            val json = request("stories/$id", false, locale = context.locale)
            val pages = json.getJSONArray("pages")
            val list = mutableListOf<StoryEntity>()
            for (i in 0 until pages.length()) {
                list.add(StoryEntity(pages.getJSONObject(i)))
            }
            if (list.isEmpty()) {
                null
            } else {
                StoryEntity.Stories(id, list.toList())
            }
        } catch (e: Throwable) {
            CrashReporter.recordException(e)
            null
        }
    }

    suspend fun resolveCountry(): String? = withContext(Dispatchers.IO) {
        try {
            val json = request("my/ip", false, locale = context.locale)
            val country = json.getString("country")
            if (country.isNullOrBlank()) {
                null
            } else {
                country.uppercase()
            }
        } catch (e: Throwable) {
            CrashReporter.recordException(e)
            null
        }
    }

    fun getEthena(accountId: String): EthenaEntity? = withRetry {
        val json = request(
            "staking/ethena",
            false,
            locale = context.locale,
            queryParams = mapOf("address" to accountId)
        )
        EthenaEntity(json)
    }

}