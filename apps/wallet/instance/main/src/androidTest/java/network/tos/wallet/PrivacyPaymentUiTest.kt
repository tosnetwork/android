package network.tos.wallet

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
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

    @Test fun cancelQuotedWithdrawalOnPrivacyScreen() =
        exerciseCancellation(finalReview = true, withdrawal = true)

    @Test fun submitQuotedWithdrawalThroughTor() =
        exerciseCancellation(finalReview = true, submit = true, withdrawal = true)

    @Test fun unavailableTorStopsBeforeWithdrawalReview() {
        assumeTrue("Requires the closed Tor SOCKS fixture",
            InstrumentationRegistry.getArguments().getString("paymentTestTorUnavailable") == "true")
        exerciseCancellation(finalReview = true, torUnavailable = true, withdrawal = true)
    }

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

    @Test fun reconcileUncertainDepositWithoutResubmitting() {
        assumeTrue("Requires a saved isolated deposit with a rejected submission",
            InstrumentationRegistry.getArguments().getString("verifyRejectedIsolatedDeposit") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "network.tos.wallet.uk")
        val accounts = GlobalContext.get().get<AccountRepository>()
        val wallet = runBlocking { accounts.getWallets().single() }
        val saved = checkNotNull(runBlocking { accounts.pendingQuantumPayment(wallet) })
        check(saved.phase.name == "UNKNOWN")
        check(saved.intent.privacyDepositPrincipal == java.math.BigInteger("1000000000"))
        val original = checkNotNull(saved.signedBOC)
        val outcome = runBlocking { accounts.readQuantumPaymentOutcome(wallet) }
        check(outcome.operationId == saved.id && outcome.receipt == null &&
            outcome.next == null && outcome.authorizationExpired)
        val after = checkNotNull(runBlocking { accounts.pendingQuantumPayment(wallet) })
        check(after.phase == saved.phase && after.id == saved.id && after.signedBOC.contentEquals(original))
        val afterPriorDeposit = InstrumentationRegistry.getArguments().getString("afterPriorIsolatedDeposit") == "true"
        val expectedOrdinary = if (afterPriorDeposit) "8993307799" else "10000000000"
        val expectedPrivacy = if (afterPriorDeposit) "1999999999" else "999999999"
        check(runBlocking { accounts.readQuantumAccount(wallet).balance } == expectedOrdinary.toBigInteger())
        val privacy = runBlocking { accounts.readPrivacyBalance(wallet) }
        check(privacy is network.tos.wallet.data.account.privacy.PrivacyBalanceState.Observed &&
            privacy.summary.unspentBalance == expectedPrivacy)
        if (InstrumentationRegistry.getArguments().getString("retireRejectedIsolatedDeposit") == "true") {
            val retired = runBlocking { accounts.endExpiredQuantumAuthorization(wallet, saved.id) }
            check(retired.operationId == saved.id && retired.result.name == "EXPIRED_UNVERIFIED")
            check(runBlocking { accounts.pendingQuantumPayment(wallet) } == null)
            check(runBlocking { accounts.quantumLocalPaymentHistory(wallet) }.any { it.operationId == saved.id })
        }
    }

    @Test fun reviewAndSubmitDepositFromOrdinaryWallet() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Requires the funded isolated deposit wallet",
            arguments.getString("submitIsolatedDeposit") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "network.tos.wallet.uk")
        val dropReply = arguments.getString("dropIsolatedDepositReply") == "true"
        reviewAndSubmitDeposit(context, dropReply)
    }

    private fun reviewAndSubmitDeposit(context: android.content.Context, dropReply: Boolean) {
        val accounts = GlobalContext.get().get<AccountRepository>()
        val wallet = runBlocking { accounts.getWallets().single() }
        val before = runBlocking { accounts.readPrivacyBalance(wallet) }
            as network.tos.wallet.data.account.privacy.PrivacyBalanceState.Observed
        val principal = java.math.BigInteger("1000000000")
        val ordinaryBefore = runBlocking { accounts.readQuantumAccount(wallet).balance }
        check(ordinaryBefore > principal)
        check(runBlocking { accounts.pendingQuantumPayment(wallet) } == null)
        val pin = context.cacheDir.resolve("disposable-ui-test-pin").readText()
        check(pin.matches(Regex("[0-9]{4}")))
        fun enterPin() = pin.forEach { digit -> onView(withText(digit.toString())).perform(click()) }
        fun press(id: String) = onView(withContentDescription(id)).perform(scrollTo(), click())
        fun waitFor(id: String, matcher: org.hamcrest.Matcher<android.view.View>) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 180_000
            while (true) {
                if (runCatching { onView(withContentDescription(id)).check(matches(matcher)) }.isSuccess) return
                check(android.os.SystemClock.elapsedRealtime() < deadline) { "Timed out waiting for $id" }
                android.os.SystemClock.sleep(500)
            }
        }
        ActivityScenario.launch(RootActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.add(PrivacyWalletScreen.newInstance(wallet, activity))
                activity.supportFragmentManager.executePendingTransactions()
            }
            press("privacy.deposit")
            onView(withHint("Amount in nanoTOS")).perform(replaceText(principal.toString()))
            onView(withText("Continue")).perform(click())
            enterPin()
            if (runBlocking { accounts.quantumLocalPaymentHistory(wallet) }.isNotEmpty()) {
                waitFor("quantum.payment.new", isEnabled())
                press("quantum.payment.new")
            }
            waitFor("quantum.payment.review", isEnabled())
            press("quantum.payment.review")
            waitFor("quantum.payment.sign", isEnabled())
            waitFor("quantum.payment.details", withText(org.hamcrest.CoreMatchers.containsString("Deposit principal: 1 TOS")))
            press("quantum.payment.sign")
            enterPin()
            waitFor("quantum.payment.submit", isEnabled())
            waitFor("quantum.payment.details", withText(org.hamcrest.CoreMatchers.containsString("Estimated total debit:")))
            val signed = checkNotNull(runBlocking { accounts.pendingQuantumPayment(wallet) })
            check(signed.intent.privacyDepositPrincipal == principal)
            check(signed.signedBOC != null)
            val status = if (dropReply) "Unable to complete this step." else "Message submitted."
            val rpc = network.tos.wallet.api.tos.TosRpcSettings(context)
            val originalEndpoint = rpc.customEndpoint
            if (dropReply) {
                check(originalEndpoint == "http://10.0.2.2:20655")
                rpc.setEndpoint("http://10.0.2.2:20656")
            }
            try {
                press("quantum.payment.submit")
                waitFor("quantum.payment.status", withText(org.hamcrest.CoreMatchers.startsWith(status)))
            } finally {
                if (dropReply) rpc.setEndpoint(checkNotNull(originalEndpoint))
            }
            val pending = checkNotNull(runBlocking { accounts.pendingQuantumPayment(wallet) })
            check(pending.id == signed.id && pending.signedBOC.contentEquals(signed.signedBOC))
            check(pending.phase.name == "UNKNOWN")
            if (dropReply) scenario.onActivity { activity ->
                val payment = activity.supportFragmentManager.fragments.lastOrNull {
                    it.javaClass.simpleName == "QuantumPaymentScreen"
                }
                val diagnostic = payment?.javaClass?.getDeclaredField("diagnosticFailureStack")?.apply {
                    isAccessible = true
                }?.get(payment) as? String
                android.util.Log.i("PrivacyPaymentUiTest", "Interrupted deposit reply diagnostic: $diagnostic")
                check(diagnostic?.startsWith("java.io.") == true ||
                    diagnostic?.startsWith("java.net.") == true) { "Expected an interrupted HTTP reply: $diagnostic" }
            }
            val expected = before.summary.unspentBalance.toBigInteger() + principal
            val deadline = android.os.SystemClock.elapsedRealtime() + 120_000
            while (true) {
                val balance = runBlocking { accounts.readPrivacyBalance(wallet) }
                if (balance is network.tos.wallet.data.account.privacy.PrivacyBalanceState.Observed &&
                    balance.summary.unspentBalance.toBigInteger() == expected) break
                check(android.os.SystemClock.elapsedRealtime() < deadline) { "Pool did not credit the exact deposit principal" }
                android.os.SystemClock.sleep(1000)
            }
            val outcome = runBlocking { accounts.readQuantumPaymentOutcome(wallet) }
            check(outcome.operationId == pending.id)
            check(outcome.receipt?.outcome ==
                network.tos.wallet.data.account.pq.QuantumPaymentOutcomeReader.Outcome.SenderSent)
            val ordinaryAfter = runBlocking { accounts.readQuantumAccount(wallet).balance }
            check(ordinaryBefore - ordinaryAfter >= pending.intent.amount)
            println("Verified deposit nanoTOS: ordinary $ordinaryBefore -> $ordinaryAfter; " +
                "privacy ${before.summary.unspentBalance} -> $expected; attached ${pending.intent.amount}")
        }
    }

    @Test fun readVerifiedPrivacyBalanceFromWalletScreen() {
        val arguments = InstrumentationRegistry.getArguments()
        val expected = arguments.getString("expectedPrivacyUiBalance")
        val failRead = arguments.getString("privacyBalanceRpcFault") == "true"
        val loadingOnly = arguments.getString("verifyPrivacyBalanceLoading") == "true"
        assumeTrue("Requires a funded isolated wallet or an isolated RPC fault",
            loadingOnly || failRead || expected?.toBigDecimalOrNull()?.signum() == 1)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "network.tos.wallet.uk")
        val accounts = GlobalContext.get().get<AccountRepository>()
        val wallet = runBlocking { accounts.getWallets().single() }
        ActivityScenario.launch(RootActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.add(PrivacyWalletScreen.newInstance(wallet, activity))
                activity.supportFragmentManager.executePendingTransactions()
            }
            val balance = onView(withContentDescription("privacy.balance"))
            balance.check(matches(withText("—")))
            if (loadingOnly) {
                scenario.onActivity { activity ->
                    fun view(id: String): android.view.View {
                        val matches = ArrayList<android.view.View>()
                        activity.window.decorView.findViewsWithText(matches, id,
                            android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
                        return matches.single { it.contentDescription?.toString() == id }
                    }
                    val status = view("privacy.status") as android.widget.TextView
                    val amount = view("privacy.balance") as android.widget.TextView
                    check(view("privacy.refresh").performClick())
                    check(status.text == "Verifying pool and complete history…" && amount.text == "—")
                }
                return@use
            }
            onView(withContentDescription("privacy.refresh")).perform(click())
            val pin = context.cacheDir.resolve("disposable-ui-test-pin").readText()
            check(pin.matches(Regex("[0-9]{4}")))
            pin.forEach { digit -> onView(withText(digit.toString())).perform(click()) }
            val status = onView(withContentDescription("privacy.status"))
            val observed = if (failRead) status else balance
            val label = if (failRead)
                "Privacy balance could not be verified. Check your node and trusted pool configuration, then refresh. This is not a zero balance."
            else "$expected TOS observed"
            val deadline = android.os.SystemClock.elapsedRealtime() + 120_000
            while (true) {
                if (runCatching { observed.check(matches(withText(label))) }.isSuccess) break
                check(android.os.SystemClock.elapsedRealtime() < deadline) { "Privacy balance did not reach the expected state" }
                android.os.SystemClock.sleep(250)
            }
            if (failRead) balance.check(matches(withText("—")))
        }
    }

    @Test fun checkSavedPrivacyPaymentWithoutRetrying() {
        assumeTrue("Requires a previously accepted isolated privacy payment",
            InstrumentationRegistry.getArguments().getString("verifySavedPrivacyPayment") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "network.tos.wallet.uk")
        val accounts = GlobalContext.get().get<AccountRepository>()
        val wallet = runBlocking { accounts.getWallets().single() }
        ActivityScenario.launch(RootActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.add(PrivacyWalletScreen.newInstance(wallet, activity))
                activity.supportFragmentManager.executePendingTransactions()
            }
            onView(withContentDescription("privacy.pending")).perform(scrollTo(), click())
            val pin = context.cacheDir.resolve("disposable-ui-test-pin").readText()
            check(pin.matches(Regex("[0-9]{4}")))
            pin.forEach { digit ->
                val key = onView(org.hamcrest.Matchers.allOf(withText(digit.toString()), isCompletelyDisplayed()))
                val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
                while (!runCatching { key.check(matches(isCompletelyDisplayed())) }.isSuccess) {
                    check(android.os.SystemClock.elapsedRealtime() < deadline) { "Passcode keypad did not become visible" }
                    android.os.SystemClock.sleep(100)
                }
                key.perform(click())
            }
            val status = onView(withContentDescription("privacy.status"))
            val deadline = android.os.SystemClock.elapsedRealtime() + 60_000
            while (true) {
                if (runCatching {
                    status.check(matches(withText("The pool accepted this private payment.")))
                }.isSuccess) break
                check(android.os.SystemClock.elapsedRealtime() < deadline)
                android.os.SystemClock.sleep(250)
            }
        }
    }

    @Test fun saveEncryptedPrivacyBackupThroughDocuments() {
        assumeTrue("Requires the disposable funded privacy wallet",
            InstrumentationRegistry.getArguments().getString("verifyIsolatedPrivacyBackupPicker") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "network.tos.wallet.uk")
        val accounts = GlobalContext.get().get<AccountRepository>()
        val wallet = runBlocking { accounts.getWallets().single() }
        val device = androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
        ActivityScenario.launch(RootActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.add(PrivacyWalletScreen.newInstance(wallet, activity))
                activity.supportFragmentManager.executePendingTransactions()
            }
            onView(withContentDescription("privacy.backup.export")).perform(scrollTo(), click())
            val saveSelector = androidx.test.uiautomator.By.res("android", "button1")
            val save = checkNotNull(device.wait(androidx.test.uiautomator.Until.findObject(saveSelector), 15_000))
            if (!save.isEnabled) {
                checkNotNull(device.findObject(androidx.test.uiautomator.By.desc("Show roots"))).click()
                checkNotNull(device.wait(androidx.test.uiautomator.Until.findObject(
                    androidx.test.uiautomator.By.text(java.util.regex.Pattern.compile("Downloads|ダウンロード"))
                ), 10_000)).click()
            }
            if ("mInputShown=true" in device.executeShellCommand("dumpsys input_method")) {
                device.pressBack()
            }
            device.waitForIdle(2_000)
            android.os.SystemClock.sleep(500)
            val destination = checkNotNull(device.wait(androidx.test.uiautomator.Until.findObject(
                saveSelector.enabled(true)
            ), 10_000)) { "No writable document destination" }
            destination.click()
            check(device.wait(androidx.test.uiautomator.Until.gone(
                androidx.test.uiautomator.By.pkg("com.android.documentsui")
            ), 10_000)) { "System save did not return to the wallet" }
            val pin = context.cacheDir.resolve("disposable-ui-test-pin").readText()
            check(pin.matches(Regex("[0-9]{4}")))
            pin.forEach { digit ->
                checkNotNull(device.wait(androidx.test.uiautomator.Until.findObject(
                    androidx.test.uiautomator.By.text(digit.toString()).pkg(context.packageName)
                ), 10_000)).click()
            }
            check(device.wait(androidx.test.uiautomator.Until.hasObject(
                androidx.test.uiautomator.By.text("Encrypted privacy backup saved.")
            ), 60_000)) { "The app did not confirm saving the backup" }
        }
    }

    @Test fun exportBackupAndRefuseToOverwriteIsolatedWallet() {
        assumeTrue("Requires the disposable funded privacy wallet",
            InstrumentationRegistry.getArguments().getString("verifyIsolatedPrivacyBackup") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "network.tos.wallet.uk")
        val accounts = GlobalContext.get().get<AccountRepository>()
        val wallet = runBlocking { accounts.getWallets().single() }
        val file = java.io.File.createTempFile("isolated-privacy-backup-", ".bin", context.cacheDir)
        try {
            android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_WRITE).use { fd ->
                runBlocking { accounts.transferPrivacyBackup(wallet, fd.fd, restore = false) { false } }
            }
            check(file.length() > 0)
            android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                val refused = runCatching {
                    runBlocking { accounts.transferPrivacyBackup(wallet, fd.fd, restore = true) { false } }
                }
                check(refused.isFailure)
            }
            val balance = runBlocking { accounts.readPrivacyBalance(wallet) }
            check(balance is network.tos.wallet.data.account.privacy.PrivacyBalanceState.Observed &&
                balance.summary.unspentBalance == "2999999999")
            if (InstrumentationRegistry.getArguments().getString("restoreIsolatedPrivacyBackup") == "true") {
                check(network.tos.wallet.api.tos.TosRpcSettings(context).customEndpoint == "http://10.0.2.2:20655")
                val pending = runBlocking { accounts.privacyPendingStatus(wallet) { false } }
                check(pending.name != "NO_RECORD")
                val parent = context.noBackupFilesDir
                val original = java.io.File(parent, "tos-privacy")
                val saved = java.io.File(parent, "tos-privacy-qa-original")
                val candidate = java.io.File(parent, "tos-privacy-qa-restored")
                check(original.isDirectory && !saved.exists() && !candidate.exists())
                check(original.renameTo(saved))
                try {
                    check(runBlocking { accounts.privacyPendingStatus(wallet) { false } }.name == "NO_RECORD")
                    check(original.listFiles()?.isEmpty() == true)
                    android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                        runBlocking { accounts.transferPrivacyBackup(wallet, fd.fd, restore = true) { false } }
                    }
                    val restored = runBlocking { accounts.readPrivacyBalance(wallet) }
                    check(restored is network.tos.wallet.data.account.privacy.PrivacyBalanceState.Observed &&
                        restored.summary.unspentBalance == balance.summary.unspentBalance)
                    check(runBlocking { accounts.privacyPendingStatus(wallet) { false } } == pending)
                } finally {
                    if (original.exists()) check(original.renameTo(candidate))
                    check(saved.renameTo(original))
                    if (candidate.exists()) check(candidate.deleteRecursively())
                }
            }
        } finally { file.delete() }
    }

    @Test fun reconcileConfirmedDepositAfterRestart() {
        assumeTrue("Requires the isolated wallet with a confirmed deposit",
            InstrumentationRegistry.getArguments().getString("verifyConfirmedIsolatedDeposit") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "network.tos.wallet.uk")
        val accounts = GlobalContext.get().get<AccountRepository>()
        val wallet = runBlocking { accounts.getWallets().single() }
        val saved = checkNotNull(runBlocking { accounts.pendingQuantumPayment(wallet) })
        check(saved.phase.name == "UNKNOWN")
        check(saved.intent.privacyDepositPrincipal == java.math.BigInteger("1000000000"))
        val original = checkNotNull(saved.signedBOC)
        val outcome = runBlocking { accounts.readQuantumPaymentOutcome(wallet) }
        check(outcome.operationId == saved.id && outcome.receipt?.outcome ==
            network.tos.wallet.data.account.pq.QuantumPaymentOutcomeReader.Outcome.SenderSent)
        val after = checkNotNull(runBlocking { accounts.pendingQuantumPayment(wallet) })
        check(after.id == saved.id && after.signedBOC.contentEquals(original))
        val afterLostReply = InstrumentationRegistry.getArguments().getString("afterIsolatedDepositLostReply") == "true"
        val expectedOrdinary = if (afterLostReply) "7988451521" else "8993307799"
        val expectedPrivacy = if (afterLostReply) "2999999999" else "1999999999"
        check(runBlocking { accounts.readQuantumAccount(wallet).balance } == expectedOrdinary.toBigInteger())
        val privacy = runBlocking { accounts.readPrivacyBalance(wallet) }
        check(privacy is network.tos.wallet.data.account.privacy.PrivacyBalanceState.Observed &&
            privacy.summary.unspentBalance == expectedPrivacy)
        if (InstrumentationRegistry.getArguments().getString("retireConfirmedIsolatedDeposit") == "true") {
            check(outcome.authorizationExpired)
            val retired = runBlocking { accounts.endExpiredQuantumAuthorization(wallet, saved.id, outcome.receipt) }
            check(retired.operationId == saved.id && retired.result.name == "SENT")
            check(runBlocking { accounts.pendingQuantumPayment(wallet) } == null)
        }
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
        withdrawal: Boolean = false,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val recipient = InstrumentationRegistry.getArguments().getString("paymentTestRecipient")
        assumeTrue("Provide an isolated recipient descriptor", !recipient.isNullOrEmpty())
        check(instrumentation.targetContext.packageName == "network.tos.wallet.uk")
        val accounts = GlobalContext.get().get<AccountRepository>()
        val wallet = runBlocking { accounts.getWallets().single() }
        if (withdrawal) assumeTrue("Requires an ordinary withdrawal recipient", runCatching { org.ton.block.AddrStd.parse(requireNotNull(recipient)).workchainId == 0 }.getOrDefault(false))
        val amount = if (withdrawal) "1000000000" else "100000000"
        val poolFee = if (withdrawal) runBlocking { accounts.privacyWithdrawalFee(wallet) { false } } else null
        val debit = amount.toBigInteger() + (poolFee ?: "0").toBigInteger() + java.math.BigInteger.ONE
        val startingBalance = if (submit) {
            check(InstrumentationRegistry.getArguments().getString("submitIsolatedPayment") == "true")
            val before = runBlocking { accounts.readPrivacyBalance(wallet) }
            check(before is network.tos.wallet.data.account.privacy.PrivacyBalanceState.Observed)
            val balance = before.summary.unspentBalance.toBigInteger()
            check(balance >= debit) {
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
            onView(withContentDescription(if (withdrawal) "privacy.withdraw" else "privacy.send")).perform(scrollTo(), click())
            onView(withText("Private payment amount")).check(matches(isDisplayed()))
            if (finalReview) {
                onView(withHint("Amount in nanoTOS")).perform(replaceText(amount))
                onView(withText("Continue")).perform(click())
                val pin = instrumentation.targetContext.cacheDir.resolve("disposable-ui-test-pin").readText()
                check(pin.matches(Regex("[0-9]{4}")))
                pin.forEach { digit -> onView(withText(digit.toString())).perform(click()) }
                val review = "Amount $amount nanoTOS\nRelay fee 1 nanoTOS\nRoute Tor\nRecipient " +
                    (if (withdrawal) requireNotNull(recipient) else requireNotNull(recipient).take(20) + "…" + recipient.takeLast(20)) +
                    (poolFee?.let { "\nPool fee $it nanoTOS\nRecipient and withdrawal amount are public on chain. Tor hides your connection address from the relay; it does not make the payout untraceable." } ?: "")
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
                    check(after.summary.unspentBalance.toBigInteger() == requireNotNull(startingBalance) - debit)
                }
            }
            if (submit && withdrawal) {
                val deadline = android.os.SystemClock.elapsedRealtime() + 90_000
                while (true) {
                    val current = runBlocking { accounts.readPrivacyBalance(wallet) }
                    if (current is network.tos.wallet.data.account.privacy.PrivacyBalanceState.Observed &&
                        current.summary.unspentBalance.toBigInteger() == requireNotNull(startingBalance) - debit) break
                    check(android.os.SystemClock.elapsedRealtime() < deadline) { "Withdrawal debit not confirmed by authenticated pool history" }
                    android.os.SystemClock.sleep(1000)
                }
            }
            if (!finalReview) {
                onView(withContentDescription("privacy.status"))
                    .check(matches(withText("Refresh to obtain a verified observation.")))
            }
        }
    }
}
