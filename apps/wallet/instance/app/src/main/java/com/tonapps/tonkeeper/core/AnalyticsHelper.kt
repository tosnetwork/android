package com.tonapps.tonkeeper.core

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.UiThread
import com.aptabase.Aptabase
import com.aptabase.InitOptions
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.tonapps.blockchain.ton.TonAddressTags
import com.tonapps.extensions.hostOrNull
import com.tonapps.extensions.toUriOrNull
import com.tonapps.wallet.api.entity.ConfigEntity
import com.tonapps.wallet.api.entity.StoryEntity
import com.tonapps.wallet.data.settings.SettingsRepository
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.collections.set

class AnalyticsHelper(
    private val settingsRepository: SettingsRepository
) {

    private data class QueuedEvent(
        val eventName: String,
        val props: Map<String, Any>
    )

    private val installId: String
        get() = settingsRepository.installId

    private val isInitialized = AtomicBoolean(false)
    private val eventQueue = ConcurrentLinkedQueue<QueuedEvent>()
    private val regexPrivateData: Regex by lazy {
        Regex("[a-f0-9]{64}|0:[a-f0-9]{64}")
    }

    private fun getAddressType(address: String): String {
        if (address.startsWith("0:")) {
            return "raw"
        }
        val tags = TonAddressTags.of(address)
        return if (tags.isBounceable) "bounce" else "non-bounce"
    }

    @UiThread
    fun simpleTrackEvent(
        eventName: String,
        props: MutableMap<String, Any> = hashMapOf()
    ) {

        trackEvent(eventName, props)
    }

    private fun trackEvent(eventName: String, props: Map<String, Any> = hashMapOf()) {
        if (isInitialized.get()) {
            send(eventName, props)
        } else {
            eventQueue.offer(QueuedEvent(eventName, props))
        }
    }

    private fun send(eventName: String, props: Map<String, Any> = hashMapOf()) {
        val fixedProps = props.mapValues {
            if (it is Uri) {
                it.value.toString()
            } else {
                it.value
            }
        }.toMutableMap()
        fixedProps["firebase_user_id"] = installId
        Aptabase.instance.trackEvent(eventName, fixedProps)
    }

    @UiThread
    fun simpleTrackScreenEvent(eventName: String, from: String) {
        simpleTrackEvent(
            eventName, hashMapOf(
                "from" to from
            )
        )
    }

    fun setConfig(context: Context, config: ConfigEntity) {
        initAptabase(
            context = context,
            appKey = config.aptabaseAppKey,
            host = config.aptabaseEndpoint
        )
    }

    private fun removePrivateDataFromUrl(url: String): String {
        return url.replace(regexPrivateData, "X")
    }

    @UiThread
    fun tcRequest(url: String) {
        val props = hashMapOf(
            "dapp_url" to url
        )
        trackEvent("tc_request", props)
    }

    @UiThread
    fun swapOpen(uri: Uri, native: Boolean) {
        val props = hashMapOf(
            "provider_name" to (uri.host ?: "unknown"),
            "provider_domain" to uri
        )
        if (native) {
            props["type"] = "native"
        } else {
            props["type"] = "old"
        }
        trackEvent("swap_open", props)
    }

    @UiThread
    fun swapClick(
        jettonSymbolFrom: String,
        jettonSymbolTo: String,
        native: Boolean,
        providerName: String,
    ) {
        val props = hashMapOf(
            "jetton_symbol_from" to jettonSymbolFrom,
            "jetton_symbol_to" to jettonSymbolTo,
            "type" to if (native) "native" else "old",
            "provider_name" to providerName
        )
        trackEvent("swap_click", props)
    }

    @UiThread
    fun swapConfirm(
        jettonSymbolFrom: String,
        jettonSymbolTo: String,
        providerName: String,
        providerUrl: String,
        native: Boolean
    ) {
        val props = hashMapOf(
            "jetton_symbol_from" to jettonSymbolFrom,
            "jetton_symbol_to" to jettonSymbolTo,
            "type" to if (native) "native" else "old",
            "provider_name" to providerName,
            "provider_domain" to (providerUrl.toUriOrNull()?.hostOrNull ?: providerUrl)
        )
        trackEvent("swap_confirm", props)
    }

    @UiThread
    fun swapSuccess(
        jettonSymbolFrom: String,
        jettonSymbolTo: String,
        providerName: String,
        providerUrl: String,
        native: Boolean
    ) {
        val props = hashMapOf(
            "jetton_symbol_from" to jettonSymbolFrom,
            "jetton_symbol_to" to jettonSymbolTo,
            "type" to if (native) "native" else "old",
            "provider_name" to providerName,
            "provider_domain" to (providerUrl.toUriOrNull()?.hostOrNull ?: providerUrl)
        )
        trackEvent("swap_success", props)
    }

    @UiThread
    fun dappSharingCopy(name: String, from: String, url: String) {
        val props = hashMapOf(
            "name" to name,
            "from" to from,
            "url" to url
        )
        trackEvent("dapp_sharing_copy", props)
    }

    @UiThread
    fun tcConnect(url: String, pushEnabled: Boolean) {
        val props = hashMapOf(
            "dapp_url" to url,
            "allow_notifications" to pushEnabled
        )
        trackEvent("tc_connect", props)
    }

    @UiThread
    fun tcViewConfirm(url: String, address: String) {
        val props = hashMapOf(
            "dapp_url" to url,
            "address_type" to getAddressType(address)
        )
        trackEvent("tc_view_confirm", props)
    }

    @UiThread
    fun tcSendSuccess(url: String, address: String, feePaid: String) {
        val props = hashMapOf(
            "dapp_url" to url,
            "address_type" to getAddressType(address),
            "network_fee_paid" to feePaid
        )
        trackEvent("tc_send_success", props)
    }

    @UiThread
    fun firstLaunch(referrer: String?, deeplink: String?) {
        val props = emptyMap<String, Any>().toMutableMap()
        referrer?.let {
            props["referrer"] = it
        }
        deeplink?.let {
            props["deeplink"] = it
        }
        trackEvent("first_launch", props)
    }

    @UiThread
    fun openRefDeeplink(deeplink: String) {
        val props = hashMapOf(
            "deeplink" to deeplink
        )
        trackEvent("ads_deeplink", props)
    }

    @UiThread
    fun batterySuccess(
        type: String,
        promo: String,
        token: String,
        size: String? = null
    ) {
        simpleTrackEvent(
            "battery_success", hashMapOf(
                "type" to type,
                "promo" to promo,
                "jetton" to token,
                "size" to (size ?: "null")
            )
        )
    }

    @UiThread
    fun onRampOpen(source: String) {
        simpleTrackScreenEvent("onramp_open", source)
    }

    @UiThread
    fun onRampEnterAmount(
        type: String,
        sellAsset: String,
        buyAsset: String,
        countryCode: String
    ) {
        val props = hashMapOf(
            "type" to type,
            "sell_asset" to sellAsset,
            "buy_asset" to buyAsset,
            "country_code" to countryCode
        )
        trackEvent("onramp_enter_amount", props)
    }

    @UiThread
    fun onRampOpenWebview(
        type: String,
        sellAsset: String,
        buyAsset: String,
        countryCode: String,
        paymentMethod: String?,
        providerName: String,
        providerDomain: String
    ) {

        fun fixPaymentMethodName(value: String): String {
            return when (value) {
                "card" -> "Credit Card"
                "google_pay" -> "Google Pay"
                "paypal" -> "PayPal"
                "revolut" -> "Revolut"
                else -> value
            }
        }

        val props = hashMapOf(
            "type" to type,
            "sell_asset" to sellAsset,
            "buy_asset" to buyAsset,
            "country_code" to countryCode,
            "payment_method" to (paymentMethod?.let(::fixPaymentMethodName) ?: "unknown"),
            "provider_name" to providerName,
            "provider_domain" to providerDomain
        )
        trackEvent("onramp_continue_to_provider", props)
    }

    @UiThread
    fun onRampClick(
        type: String,
        placement: String,
        location: String,
        name: String,
        url: String
    ) {
        trackEvent(
            "onramp_click", hashMapOf(
                "type" to type,
                "placement" to placement,
                "location" to location,
                "name" to name,
                "url" to url
            )
        )
    }

    @UiThread
    fun trackPushClick(pushId: String, payload: String) {
        trackEvent(
            "push_click", hashMapOf(
                "push_id" to pushId,
                "payload" to removePrivateDataFromUrl(payload)
            )
        )
    }

    @UiThread
    fun trackStoryClick(
        storiesId: String,
        button: StoryEntity.Button,
        index: Int
    ) {
        trackEvent(
            "story_click", hashMapOf(
                "story_id" to storiesId,
                "button_title" to button.title,
                "button_type" to button.type,
                "button_payload" to button.payload,
                "page_number" to index + 1
            )
        )
    }

    @UiThread
    fun trackStoryView(storiesId: String, index: Int) {
        trackEvent(
            "story_page_view", hashMapOf(
                "story_id" to storiesId,
                "page_number" to index
            )
        )
    }

    @UiThread
    fun trackStoryOpen(storiesId: String, from: String) {
        trackEvent(
            "story_open", hashMapOf(
                "story_id" to storiesId,
                "from" to from
            )
        )
    }

    @UiThread
    fun trackEventClickDApp(
        url: String,
        name: String,
        source: String,
        country: String,
    ) {
        trackEvent(
            "click_dapp", hashMapOf(
                "url" to url,
                "name" to name,
                "from" to source,
                "location" to country
            )
        )
    }

    private fun processEventQueue() {
        if (!isInitialized.get()) {
            return
        }
        while (eventQueue.isNotEmpty()) {
            val queuedEvent = eventQueue.poll()
            if (queuedEvent != null) {
                send(queuedEvent.eventName, queuedEvent.props)
            }
        }
    }

    private fun initAptabase(
        context: Context,
        appKey: String,
        host: String
    ) {
        try {
            val options = InitOptions(
                host = host
            )
            Aptabase.instance.initialize(context, appKey, options)
            if (isInitialized.compareAndSet(false, true)) {
                processEventQueue()
            }
        } catch (e: Throwable) {
            FirebaseCrashlytics.getInstance().recordException(e)
        }
    }
}