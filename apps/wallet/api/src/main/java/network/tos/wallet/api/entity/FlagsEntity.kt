package network.tos.wallet.api.entity

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import org.json.JSONObject

@Parcelize
data class FlagsEntity(
    val disableSwap: Boolean,
    val disableExchangeMethods: Boolean,
    val disableDApps: Boolean,
    val disableSigner: Boolean,
    val safeModeEnabled: Boolean,
    val disableStaking: Boolean,
    val disableTron: Boolean,
    val disableBattery: Boolean,
    val disableGasless: Boolean,
    val disableUsde: Boolean,
    val disableNativeSwap: Boolean,
    val disableOnboardingStory: Boolean,
    val disableNfts: Boolean
) : Parcelable {

    constructor(json: JSONObject) : this(
        disableSwap = json.optBoolean("disable_swap", true),
        disableExchangeMethods = json.optBoolean("disable_exchange_methods", true),
        disableDApps = json.optBoolean("disable_dapps", true),
        disableSigner = json.optBoolean("disable_signer", false),
        safeModeEnabled = json.optBoolean("safe_mode_enabled", false),
        disableStaking = json.optBoolean("disable_staking", true),
        disableTron = json.optBoolean("disable_tron", true),
        disableBattery = json.optBoolean("disable_battery", true),
        disableGasless = json.optBoolean("disable_gaseless", true),
        disableUsde = json.optBoolean("disable_usde", true),
        disableNativeSwap = json.optBoolean("disable_native_swap", true),
        disableOnboardingStory = json.optBoolean("disable_onboarding_story", true),
        disableNfts = json.optBoolean("disable_nfts", true)
    )

    // TOS V1: disable by default every feature that depends on deferred external services.
    // These have no backend on TOS-owned infrastructure; enabling them would call external hosts.
    constructor() : this(
        disableSwap = true,
        disableExchangeMethods = true,
        disableDApps = true,
        disableSigner = false,
        safeModeEnabled = false,
        disableStaking = true,
        disableTron = true,
        disableBattery = true,
        disableGasless = true,
        disableUsde = true,
        disableNativeSwap = true,
        disableOnboardingStory = true,
        disableNfts = true
    )
}
