package com.tonapps.wallet.api.entity

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
        disableSwap = json.optBoolean("disable_swap", false),
        disableExchangeMethods = json.optBoolean("disable_exchange_methods", false),
        disableDApps = json.optBoolean("disable_dapps", false),
        disableSigner = json.optBoolean("disable_signer", false),
        safeModeEnabled = json.optBoolean("safe_mode_enabled", false),
        disableStaking = json.optBoolean("disable_staking", false),
        disableTron = json.optBoolean("disable_tron", false),
        disableBattery = json.optBoolean("disable_battery", false),
        disableGasless = json.optBoolean("disable_gaseless", false),
        disableUsde = json.optBoolean("disable_usde", false),
        disableNativeSwap = json.optBoolean("disable_native_swap", false),
        disableOnboardingStory = json.optBoolean("disable_onboarding_story", false),
        disableNfts = json.optBoolean("disable_nfts", false)
    )

    // TOS (Phase 0): disable by default every feature that depends on Tonkeeper-only external services.
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
        disableNfts = false
    )
}