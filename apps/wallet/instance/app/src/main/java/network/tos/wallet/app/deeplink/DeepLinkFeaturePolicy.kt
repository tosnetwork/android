package network.tos.wallet.app.deeplink

import network.tos.wallet.api.entity.FlagsEntity

/**
 * Central feature boundary for every supported deep-link scheme.
 * Parsing a valid inherited link does not imply that its product feature is enabled.
 */
object DeepLinkFeaturePolicy {

    fun isAllowed(route: DeepLinkRoute, flags: FlagsEntity): Boolean = when (route) {
        is DeepLinkRoute.Staking,
        is DeepLinkRoute.StakingPool -> !flags.disableStaking

        is DeepLinkRoute.Battery -> !flags.disableBattery

        is DeepLinkRoute.DApp,
        is DeepLinkRoute.Tabs.Browser -> !flags.disableDApps

        is DeepLinkRoute.Purchase,
        is DeepLinkRoute.Exchange -> !flags.disableExchangeMethods

        is DeepLinkRoute.Swap -> !flags.disableSwap

        is DeepLinkRoute.Tabs.Collectibles,
        is DeepLinkRoute.Jetton -> !flags.disableNfts

        is DeepLinkRoute.Story -> !flags.disableOnboardingStory
        is DeepLinkRoute.Signer -> !flags.disableSigner
        else -> true
    }
}
