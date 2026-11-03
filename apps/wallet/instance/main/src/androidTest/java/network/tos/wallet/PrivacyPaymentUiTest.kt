package network.tos.wallet

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import network.tos.wallet.app.ui.screen.privacy.PrivacyWalletScreen
import network.tos.wallet.app.ui.screen.root.RootActivity
import network.tos.wallet.data.account.AccountRepository
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

@RunWith(AndroidJUnit4::class)
class PrivacyPaymentUiTest {
    @Test fun cancelAmountOnPrivacyPaymentScreen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val recipient = InstrumentationRegistry.getArguments().getString("paymentTestRecipient")
        assumeTrue("Provide an isolated recipient descriptor", !recipient.isNullOrEmpty())
        check(instrumentation.targetContext.packageName == "network.tos.wallet.uk")
        val accounts = GlobalContext.get().get<AccountRepository>()
        val wallet = runBlocking { accounts.getWallets().single() }
        ActivityScenario.launch(RootActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.add(PrivacyWalletScreen.newInstance(wallet, activity))
                activity.supportFragmentManager.executePendingTransactions()
            }
            onView(withContentDescription("privacy.recipient")).perform(replaceText(requireNotNull(recipient)))
            onView(withContentDescription("privacy.send")).perform(click())
            onView(withText("Private payment amount")).check(matches(isDisplayed()))
            onView(withText("Cancel")).perform(click())
            onView(withContentDescription("privacy.status"))
                .check(matches(withText("Refresh to obtain a verified observation.")))
        }
    }
}
