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

    @Test fun submitQuotedPaymentOnPrivacyPaymentScreen() = exerciseCancellation(finalReview = true, submit = true)

    @Test fun recoverUncertainPaymentAfterBackground() {
        assumeTrue("Requires a one-shot submit-response fault on the isolated relay",
            InstrumentationRegistry.getArguments().getString("backgroundAfterLostResponse") == "true")
        exerciseCancellation(finalReview = true, submit = true, backgroundAfterLostResponse = true)
    }

    @Test fun unavailableTorStopsBeforePaymentReview() {
        assumeTrue("Disconnect the isolated Tor proxy before running this test",
            InstrumentationRegistry.getArguments().getString("paymentTestTorUnavailable") == "true")
        exerciseCancellation(finalReview = true, torUnavailable = true)
    }

    @Test fun copyPrivateReceivingDescriptor() {
        assumeTrue("Requires the isolated privacy pool",
            InstrumentationRegistry.getArguments().getString("copyIsolatedReceipt") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "network.tos.wallet.uk")
        val accounts = GlobalContext.get().get<AccountRepository>()
        val wallet = runBlocking { accounts.getWallets().single() }
        ActivityScenario.launch(RootActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.add(PrivacyWalletScreen.newInstance(wallet, activity))
                activity.supportFragmentManager.executePendingTransactions()
            }
            onView(withContentDescription("privacy.receive")).perform(click())
            val pin = context.cacheDir.resolve("disposable-ui-test-pin").readText()
            check(pin.matches(Regex("[0-9]{4}")))
            pin.forEach { digit -> onView(withText(digit.toString())).perform(click()) }
            val copy = onView(withContentDescription("privacy.receipt.copy"))
            val deadline = android.os.SystemClock.elapsedRealtime() + 60_000
            while (true) {
                if (runCatching { copy.check(matches(isDisplayed())) }.isSuccess) break
                check(android.os.SystemClock.elapsedRealtime() < deadline) { "No private receiving descriptor appeared" }
                android.os.SystemClock.sleep(250)
            }
            copy.perform(click())
            onView(withContentDescription("privacy.receipt")).check { view, missing ->
                if (missing != null) throw missing
                val displayed = (view as android.widget.TextView).text.toString()
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                check(displayed.length == 3432 && copied == displayed) {
                    "Clipboard must contain the complete displayed private receiving descriptor"
                }
            }
        }
    }

    private fun exerciseCancellation(
        finalReview: Boolean,
        submit: Boolean = false,
        torUnavailable: Boolean = false,
        backgroundAfterLostResponse: Boolean = false,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val recipient = InstrumentationRegistry.getArguments().getString("paymentTestRecipient")
        assumeTrue("Provide an isolated recipient descriptor", !recipient.isNullOrEmpty())
        check(instrumentation.targetContext.packageName == "network.tos.wallet.uk")
        val accounts = GlobalContext.get().get<AccountRepository>()
        val wallet = runBlocking { accounts.getWallets().single() }
        val startingBalance = if (submit) {
            check(InstrumentationRegistry.getArguments().getString("submitIsolatedPayment") == "true")
            val before = runBlocking { accounts.readPrivacyBalance(wallet) }
            check(before is network.tos.wallet.data.account.privacy.PrivacyBalanceState.Observed)
            val balance = before.summary.unspentBalance.toBigInteger()
            check(balance >= 100_000_001.toBigInteger()) {
                "Isolated sender needs at least 100000001 nanoTOS, observed $balance"
            }
            android.util.Log.i("PrivacyPaymentUiTest", "Isolated sender starting balance: $balance nanoTOS")
            balance
        } else null
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
                val failure = "Unable to complete private payment through Tor. Check pending payment before trying again."
                if (torUnavailable) {
                    val deadline = android.os.SystemClock.elapsedRealtime() + 300_000
                    while (android.os.SystemClock.elapsedRealtime() < deadline) {
                        val failed = runCatching {
                            onView(withContentDescription("privacy.status")).check(matches(withText(failure)))
                        }.isSuccess
                        if (failed) {
                            onView(withText(review)).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
                            return
                        }
                        android.os.SystemClock.sleep(1000)
                    }
                    error("Unavailable Tor did not produce the expected payment error")
                }
                val deadline = android.os.SystemClock.elapsedRealtime() + 600_000
                var displayed = false
                while (!displayed && android.os.SystemClock.elapsedRealtime() < deadline) {
                    displayed = runCatching { onView(withText(review)).check(matches(isDisplayed())) }.isSuccess
                    if (!displayed) {
                        val failed = runCatching {
                            onView(withContentDescription("privacy.status")).check(matches(withText(failure)))
                        }.isSuccess
                        check(!failed) { failure }
                        android.os.SystemClock.sleep(1000)
                    }
                }
                check(displayed) { "The verified amount, fee, route and recipient must appear in the final dialog" }
            }
            onView(withText(if (submit) "Continue" else "Cancel")).perform(click())
            if (submit) {
                val deadline = android.os.SystemClock.elapsedRealtime() + 180_000
                val expectedStatus = if (backgroundAfterLostResponse)
                    "Unable to complete private payment through Tor. Check pending payment before trying again."
                else "Relay responded. Check pending payment to verify chain confirmation."
                var responded = false
                while (!responded && android.os.SystemClock.elapsedRealtime() < deadline) {
                    responded = runCatching {
                        onView(withContentDescription("privacy.status")).check(matches(
                            withText(expectedStatus)))
                    }.isSuccess
                    if (!responded) android.os.SystemClock.sleep(1000)
                }
                check(responded) { "The approved payment did not reach the relay response state" }
                if (backgroundAfterLostResponse) {
                    val pid = android.os.Process.myPid()
                    scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                    scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                    check(android.os.Process.myPid() == pid)
                    onView(withContentDescription("privacy.retry")).perform(click())
                    val pin = instrumentation.targetContext.cacheDir.resolve("disposable-ui-test-pin").readText()
                    check(pin.matches(Regex("[0-9]{4}")))
                    pin.forEach { digit -> onView(withText(digit.toString())).perform(click()) }
                    val recoveryDeadline = android.os.SystemClock.elapsedRealtime() + 90_000
                    while (true) {
                        if (runCatching {
                                onView(withContentDescription("privacy.status")).check(matches(
                                    withText("No unresolved payment. Refresh to verify your balance.")))
                            }.isSuccess) break
                        check(android.os.SystemClock.elapsedRealtime() < recoveryDeadline) {
                            "The original private payment was not recovered after backgrounding"
                        }
                        android.os.SystemClock.sleep(250)
                    }
                    val after = runBlocking { accounts.readPrivacyBalance(wallet) }
                    check(after is network.tos.wallet.data.account.privacy.PrivacyBalanceState.Observed)
                    check(after.summary.unspentBalance.toBigInteger() == requireNotNull(startingBalance) - 100_000_001.toBigInteger())
                }
            }
            if (!finalReview) {
                onView(withContentDescription("privacy.status"))
                    .check(matches(withText("Refresh to obtain a verified observation.")))
            }
        }
    }
}
