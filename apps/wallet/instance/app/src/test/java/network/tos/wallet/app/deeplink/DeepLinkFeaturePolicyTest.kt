package network.tos.wallet.app.deeplink

import network.tos.wallet.api.entity.FlagsEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepLinkFeaturePolicyTest {

    private val allFeaturesEnabled = FlagsEntity(
        disableSwap = false,
        disableExchangeMethods = false,
        disableDApps = false,
        disableSigner = false,
        safeModeEnabled = false,
        disableStaking = false,
        disableTron = false,
        disableBattery = false,
        disableGasless = false,
        disableUsde = false,
        disableNativeSwap = false,
        disableOnboardingStory = false,
        disableNfts = false,
    )

    @Test
    fun `offline V1 flags reject every deferred route`() {
        val flags = FlagsEntity()
        val routes = listOf(
            DeepLinkRoute.Staking,
            DeepLinkRoute.StakingPool("pool"),
            DeepLinkRoute.Battery(null, null),
            DeepLinkRoute.DApp("https://example.com"),
            DeepLinkRoute.Purchase,
            DeepLinkRoute.Exchange("method"),
            DeepLinkRoute.Swap("TOS", null),
            DeepLinkRoute.Tabs.Collectibles("test"),
            DeepLinkRoute.Jetton("asset"),
            DeepLinkRoute.Story("story"),
        )

        routes.forEach { route ->
            assertFalse("Expected $route to be rejected", DeepLinkFeaturePolicy.isAllowed(route, flags))
        }
    }

    @Test
    fun `native V1 routes remain allowed`() {
        val flags = FlagsEntity()
        val routes = listOf(
            DeepLinkRoute.Tabs.Main("test"),
            DeepLinkRoute.Tabs.Activity("test"),
            DeepLinkRoute.Send,
            DeepLinkRoute.Receive,
            DeepLinkRoute.Settings,
            DeepLinkRoute.Backups,
        )

        routes.forEach { route ->
            assertTrue("Expected $route to be allowed", DeepLinkFeaturePolicy.isAllowed(route, flags))
        }
    }

    @Test
    fun `each flag controls its own deferred route family`() {
        val cases = listOf(
            DeepLinkRoute.Staking to allFeaturesEnabled.copy(disableStaking = true),
            DeepLinkRoute.StakingPool("pool") to allFeaturesEnabled.copy(disableStaking = true),
            DeepLinkRoute.Battery(null, null) to allFeaturesEnabled.copy(disableBattery = true),
            DeepLinkRoute.DApp("https://example.com") to allFeaturesEnabled.copy(disableDApps = true),
            DeepLinkRoute.Purchase to allFeaturesEnabled.copy(disableExchangeMethods = true),
            DeepLinkRoute.Exchange("method") to allFeaturesEnabled.copy(disableExchangeMethods = true),
            DeepLinkRoute.Swap("TOS", null) to allFeaturesEnabled.copy(disableSwap = true),
            DeepLinkRoute.Tabs.Collectibles("test") to allFeaturesEnabled.copy(disableNfts = true),
            DeepLinkRoute.Jetton("asset") to allFeaturesEnabled.copy(disableNfts = true),
            DeepLinkRoute.Story("story") to allFeaturesEnabled.copy(disableOnboardingStory = true),
        )

        cases.forEach { (route, flags) ->
            assertTrue("Expected $route when enabled", DeepLinkFeaturePolicy.isAllowed(route, allFeaturesEnabled))
            assertFalse("Expected $route to honor its flag", DeepLinkFeaturePolicy.isAllowed(route, flags))
        }
    }
}
