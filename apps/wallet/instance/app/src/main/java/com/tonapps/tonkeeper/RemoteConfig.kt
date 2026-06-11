package com.tonapps.tonkeeper

import android.content.Context
import com.tonapps.wallet.api.API

/**
 * TOS: feature flags are local constants, not fetched from Firebase Remote Config.
 * The wallet does not contact any external config service. Kept as a drop-in so the
 * existing call sites (fetchAndActivate / the flag getters) remain unchanged.
 */
class RemoteConfig(context: Context, private val api: API) {

    fun fetchAndActivate() {
        // no-op: no remote config service
    }

    val inAppUpdateAvailable: Boolean
        get() = false

    val nativeOnrmapEnabled: Boolean
        get() = false

    val isCountryPickerDisable: Boolean
        get() = false

    val hardcodedCountryCode: String?
        get() = null

    val isDappsDisable: Boolean
        get() = true

    val isOnboardingStoriesEnabled: Boolean
        get() = true
}
