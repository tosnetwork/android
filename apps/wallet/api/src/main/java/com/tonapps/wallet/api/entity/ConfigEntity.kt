package com.tonapps.wallet.api.entity

import android.net.Uri
import android.os.Parcelable
import android.util.Log
import com.tonapps.extensions.toStringList
import com.tonapps.icu.Coins
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import org.json.JSONObject
import androidx.core.net.toUri
import com.tonapps.wallet.api.Constants

@Parcelize
data class ConfigEntity(
    val empty: Boolean,
    val supportLink: String,
    val nftExplorer: String,
    val transactionExplorer: String,
    val accountExplorer: String,
    val mercuryoSecret: String,
    val tonapiMainnetHost: String,
    val tonapiTestnetHost: String,
    val tonConnectBridgeHost: String,
    val stonfiUrl: String,
    val tonNFTsMarketplaceEndpoint: String,
    val directSupportUrl: String,
    val tonkeeperNewsUrl: String,
    val tonCommunityUrl: String,
    val tonCommunityChatUrl: String,
    val tonApiV2Key: String,
    val featuredPlayInterval: Int,
    val flags: FlagsEntity,
    val faqUrl: String,
    val aptabaseEndpoint: String,
    val aptabaseAppKey: String,
    val scamEndpoint: String,
    val batteryHost: String,
    val batteryTestnetHost: String,
    val batteryBeta: Boolean,
    val batterySendDisabled: Boolean,
    val disableBatteryIapModule: Boolean,
    val disableBatteryCryptoRechargeModule: Boolean,
    val batteryMaxInputAmount: String,
    val batteryRefundEndpoint: String,
    val batteryPromoDisable: Boolean,
    val stakingInfoUrl: String,
    val tonapiSSEEndpoint: String,
    val tonapiSSETestnetEndpoint: String,
    val iapPackages: List<IAPPackageEntity>,
    val burnZeroDomain: String,
    val scamAPIURL: String,
    val reportAmount: Coins,
    val stories: List<String>,
    val apkDownloadUrl: String?,
    val apkName: AppVersion?,
    val tronApiUrl: String,
    val enabledStaking: List<String>,
    val qrScannerExtends: List<QRScannerExtendsEntity>,
    val region: String,
    val tonkeeperApiUrl: String,
    val tronSwapUrl: String,
    val tronSwapTitle: String,
    val tronApiKey: String? = null,
    val privacyPolicyUrl: String,
    val termsOfUseUrl: String,
    val webSwapsUrl: String,
): Parcelable {

    @IgnoredOnParcel
    val swapUri: Uri
        get() = stonfiUrl.toUri()

    @IgnoredOnParcel
    val domains: List<String> by lazy {
        listOf(tonapiMainnetHost, tonapiTestnetHost, tonapiSSEEndpoint, tonapiSSETestnetEndpoint, tonConnectBridgeHost, Constants.TOS_API_MAINNET)
    }

    @IgnoredOnParcel
    val apk: ApkEntity? by lazy {
        val name = apkName ?: return@lazy null
        val url = apkDownloadUrl ?: return@lazy null
        ApkEntity(url, name)
    }

    constructor(json: JSONObject, debug: Boolean) : this(
        empty = false,
        supportLink = json.getString("supportLink"),
        nftExplorer = json.getString("NFTOnExplorerUrl"),
        transactionExplorer = json.getString("transactionExplorer"),
        accountExplorer = json.getString("accountExplorer"),
        mercuryoSecret = json.getString("mercuryoSecret"),
        tonapiMainnetHost = json.getString("tonapiMainnetHost"),
        tonapiTestnetHost = json.getString("tonapiTestnetHost"),
        tonConnectBridgeHost = json.optString("ton_connect_bridge", Constants.TOS_BRIDGE),
        stonfiUrl = json.getString("stonfiUrl"),
        tonNFTsMarketplaceEndpoint = json.getString("tonNFTsMarketplaceEndpoint"),
        directSupportUrl = json.getString("directSupportUrl"),
        tonkeeperNewsUrl = json.getString("tonkeeperNewsUrl"),
        tonCommunityUrl = json.getString("tonCommunityUrl"),
        tonCommunityChatUrl = json.getString("tonCommunityChatUrl"),
        tonApiV2Key = json.getString("tonApiV2Key"),
        featuredPlayInterval = json.optInt("featured_play_interval", 3000),
        flags = FlagsEntity(json.getJSONObject("flags")), /*if (debug) {
            FlagsEntity()
        } else {
            FlagsEntity(json.getJSONObject("flags"))
        },*/
        faqUrl = json.getString("faq_url"),
        aptabaseEndpoint = json.getString("aptabaseEndpoint"),
        aptabaseAppKey = json.getString("aptabaseAppKey"),
        scamEndpoint = json.optString("scamEndpoint", ""),
        batteryHost = json.optString("batteryHost", ""),
        batteryTestnetHost = json.optString("batteryTestnetHost", ""),
        batteryBeta = json.optBoolean("battery_beta", true),
        batterySendDisabled = json.optBoolean("disable_battery_send", false),
        disableBatteryIapModule = json.optBoolean("disable_battery_iap_module", false),
        disableBatteryCryptoRechargeModule = json.optBoolean("disable_battery_crypto_recharge_module", false),
        batteryMaxInputAmount = json.optString("batteryMaxInputAmount", "3"),
        batteryRefundEndpoint = json.optString("batteryRefundEndpoint", ""),
        batteryPromoDisable = json.optBoolean("disable_battery_promo_module", true),
        stakingInfoUrl = json.getString("stakingInfoUrl"),
        tonapiSSEEndpoint = json.optString("tonapi_sse_endpoint", Constants.TOS_SSE_MAINNET),
        tonapiSSETestnetEndpoint = json.optString("tonapi_sse_testnet_endpoint", Constants.TOS_SSE_TESTNET),
        iapPackages = json.optJSONArray("iap_packages")?.let { array ->
            (0 until array.length()).map { IAPPackageEntity(array.getJSONObject(it)) }
        } ?: emptyList(),
        burnZeroDomain = json.optString("burnZeroDomain", "UQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAJKZ"), // tonkeeper-zero.ton
        scamAPIURL = json.optString("scam_api_url", ""),
        reportAmount = Coins.of(json.optString("reportAmount") ?: "0.03"),
        stories = json.getJSONArray("stories").toStringList(),
        apkDownloadUrl = json.optString("apk_download_url"),
        apkName = json.optString("apk_name")?.let { AppVersion(it.removePrefix("v")) },
        tronApiUrl = json.optString("tron_api_url", ""),
        enabledStaking = json.optJSONArray("enabled_staking")?.let { array ->
            (0 until array.length()).map { array.getString(it) }
        } ?: emptyList(),
        qrScannerExtends = json.optJSONArray("qr_scanner_extends")?.let { array ->
            QRScannerExtendsEntity.of(array)
        } ?: emptyList(),
        region = json.getString("region"),
        tonkeeperApiUrl = json.optString("tonkeeper_api_url", Constants.TOS_API_MAINNET),
        tronSwapUrl = json.optString("tron_swap_url", ""),
        tronSwapTitle = json.optString("tron_swap_title", ""),
        // tronApiKey = json.optString("tron_api_key"),
        privacyPolicyUrl = json.getString("privacy_policy"),
        termsOfUseUrl = json.getString("terms_of_use"),
        webSwapsUrl = json.optString("web_swaps_url", Constants.SWAP_PREFIX)
    )

    // TOS (Phase 0): the default config points entirely at TOS-owned infrastructure (see Constants).
    // Endpoints for Tonkeeper-only external services (battery/swap/tron/scam/analytics/stories, etc.)
    // are left empty; together with FlagsEntity disabled by default, the app no longer calls
    // tonkeeper.com / tonapi.io, etc.
    constructor() : this(
        empty = true,
        supportLink = Constants.TOS_SUPPORT_URL,
        nftExplorer = Constants.TOS_EXPLORER_NFT,
        transactionExplorer = Constants.TOS_EXPLORER_TX,
        accountExplorer = Constants.TOS_EXPLORER_ACCOUNT,
        mercuryoSecret = "",
        tonapiMainnetHost = Constants.TOS_API_MAINNET,
        tonapiTestnetHost = Constants.TOS_API_TESTNET,
        tonConnectBridgeHost = Constants.TOS_BRIDGE,
        stonfiUrl = "",
        tonNFTsMarketplaceEndpoint = Constants.TOS_EXPLORER,
        directSupportUrl = Constants.TOS_SUPPORT_URL,
        tonkeeperNewsUrl = "",
        tonCommunityUrl = "",
        tonCommunityChatUrl = "",
        tonApiV2Key = "",
        featuredPlayInterval = 3000,
        flags = FlagsEntity(),
        faqUrl = "",
        aptabaseEndpoint = "",
        aptabaseAppKey = "",
        scamEndpoint = "",
        batteryHost = "",
        batteryTestnetHost = "",
        batteryBeta = true,
        batterySendDisabled = true,
        disableBatteryIapModule = true,
        disableBatteryCryptoRechargeModule = true,
        batteryMaxInputAmount = "3",
        batteryRefundEndpoint = "",
        batteryPromoDisable = true,
        stakingInfoUrl = "",
        tonapiSSEEndpoint = Constants.TOS_SSE_MAINNET,
        tonapiSSETestnetEndpoint = Constants.TOS_SSE_TESTNET,
        iapPackages = emptyList(),
        burnZeroDomain = "UQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAJKZ",
        scamAPIURL = "",
        reportAmount = Coins.of("0.03"),
        stories = emptyList(),
        apkDownloadUrl = null,
        apkName = null,
        tronApiUrl = "",
        enabledStaking = emptyList(),
        qrScannerExtends = emptyList(),
        region = "US",
        tonkeeperApiUrl = Constants.TOS_API_MAINNET,
        tronSwapUrl = "",
        tronSwapTitle = "",
        privacyPolicyUrl = Constants.TOS_PRIVACY_URL,
        termsOfUseUrl = Constants.TOS_TERMS_URL,
        webSwapsUrl = Constants.SWAP_PREFIX
    )

    fun formatTransactionExplorer(testnet: Boolean, tron: Boolean, hash: String): String {
        // TOS: always use the TOS explorer; TRON is disabled by default, params kept for signature compatibility.
        return transactionExplorer.format(hash)
    }

    companion object {
        val default = ConfigEntity()
    }
}