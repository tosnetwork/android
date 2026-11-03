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
    @Test fun cancelAmountOnPrivacyPaymentScreen() = exerciseCancellation(finalReview = false)

    @Test fun cancelQuotedPaymentOnPrivacyPaymentScreen() = exerciseCancellation(finalReview = true)

    private fun exerciseCancellation(finalReview: Boolean) {
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
            if (finalReview) {
                onView(withHint("Amount in nanoTOS")).perform(replaceText("100000000"))
                onView(withText("Continue")).perform(click())
                val pin = instrumentation.targetContext.cacheDir.resolve("disposable-ui-test-pin").readText()
                check(pin.matches(Regex("[0-9]{4}")))
                pin.forEach { digit -> onView(withText(digit.toString())).perform(click()) }
                val review = "Amount 100000000 nanoTOS\nRelay fee 1 nanoTOS\nRoute Tor\nRecipient " +
                    requireNotNull(recipient).take(20) + "…" + recipient.takeLast(20)
                val deadline = android.os.SystemClock.elapsedRealtime() + 600_000
                var displayed = false
                while (!displayed && android.os.SystemClock.elapsedRealtime() < deadline) {
                    displayed = runCatching { onView(withText(review)).check(matches(isDisplayed())) }.isSuccess
                    if (!displayed) android.os.SystemClock.sleep(1000)
                }
                check(displayed) { "The verified amount, fee, route and recipient must appear in the final dialog" }
            }
            onView(withText("Cancel")).perform(click())
            if (!finalReview) {
                onView(withContentDescription("privacy.status"))
                    .check(matches(withText("Refresh to obtain a verified observation.")))
            }
        }
    }
}
