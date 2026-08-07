package network.tos.wallet

import android.content.ComponentName
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import network.tos.blockchain.ton.contract.WalletVersion
import network.tos.icu.CurrencyFormatter
import network.tos.icu.Coins
import network.tos.qr.QR
import network.tos.security.Sodium
import network.tos.wallet.api.API
import network.tos.wallet.data.account.AccountRepository
import network.tos.wallet.data.account.Wallet
import network.tos.wallet.data.passcode.PasscodeManager
import network.tos.wallet.data.passcode.source.PasscodeStore
import network.tos.wallet.api.entity.TokenEntity
import network.tos.wallet.app.helper.DateHelper
import network.tos.wallet.app.ui.screen.qr.QRScreen
import network.tos.icu.CurrencyFormatter.withCustomSymbol
import org.koin.core.context.GlobalContext
import org.ton.mnemonic.Mnemonic
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

@RunWith(AndroidJUnit4::class)
class V1ProductUiTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun cleanLaunchUsesTosBrandAndOnlyV1EntryPoints() {
        launch()

        assertTrue(waitText("TOS Wallet"))
        assertTrue(waitText("Create new wallet"))
        assertTrue(waitText("Import existing wallet"))
        assertNoReachableDeferredCopy()

        clickText("Import existing wallet")
        assertTrue(waitText("Import wallet"))
        assertFalse(hasText("Watch Account"))
        assertFalse(hasText("Testnet"))
        assertNoReachableDeferredCopy()
    }

    @Test
    fun createWalletOpensPasscodeAndCancelLeavesNoWallet() {
        launch()
        clickText("Create new wallet")

        assertTrue(waitText("Create passcode", 300_000))
        device.pressBack()
        device.pressBack()
        device.pressBack()
        launch()
        assertTrue(waitText("Create new wallet"))
    }

    @Test
    fun validNativePhraseStartsImportAndInvalidPhrasesStayRejected() {
        launchImport()
        setPhrase(FIXTURE_MNEMONIC)
        clickText("Continue")
        assertTrue(waitText("Create passcode", 300_000))
    }

    @Test
    fun nativeImportScreenIsTwentyFourWordsOnly() {
        launchImport()

        assertTrue(waitText("24 words"))
        assertFalse(hasText("12 words"))
        assertTrue(waitText("Enter recovery phrase"))
        assertTrue(hasTextContaining("24 secret recovery words"))
    }

    @Test
    fun invalidWordCountRemainsRejectedInUi() {
        val words = FIXTURE_MNEMONIC.split(" ")
        assertRejected(words.dropLast(1).joinToString(" "))
    }

    @Test
    fun unknownMnemonicWordRemainsRejectedInUi() {
        val words = FIXTURE_MNEMONIC.split(" ").toMutableList().apply { this[0] = "notaword" }
        assertRejected(words.joinToString(" "))
    }

    @Test
    fun invalidMnemonicChecksumRemainsRejectedInUi() {
        assertRejected(List(24) { "abandon" }.joinToString(" "))
    }

    @Test
    fun cancelledImportLeavesNoWalletOrPasscode() {
        launchImport()
        setPhrase(FIXTURE_MNEMONIC)
        clickText("Continue")
        assertTrue(waitText("Create passcode", 300_000))
        repeat(3) { device.pressBack() }
        launch()
        assertTrue(waitText("Create new wallet", 15_000))
        assertTrue(runBlocking { GlobalContext.get().get<AccountRepository>().getWallets().isEmpty() })
        assertFalse(runBlocking { GlobalContext.get().get<PasscodeManager>().hasPinCode() })
        assertNoFatalCrash()
    }

    @Test
    fun passcodeMismatchRetriesAndMatchingCodesCreateWallet() {
        launchImport()
        setPhrase(FIXTURE_MNEMONIC)
        clickText("Continue")
        assertTrue(waitText("Create passcode", 300_000))
        enterPin("1234")
        assertTrue(waitText("Re-enter passcode", 10_000))
        enterPin("9999")
        assertTrue("Mismatch did not return to passcode creation", waitText("Create passcode", 10_000))
        assertTrue(runBlocking { GlobalContext.get().get<AccountRepository>().getWallets().isEmpty() })
        assertFalse(runBlocking { GlobalContext.get().get<PasscodeManager>().hasPinCode() })

        enterPin("1234")
        assertTrue(waitText("Re-enter passcode", 10_000))
        enterPin("1234")
        assertTrue(waitText("Customize your Wallet", 30_000))
        val name = device.wait(Until.findObject(By.res(APP_ID, "input_field")), 10_000)
        assertNotNull(name)
        name.text = "Passcode Test Wallet"
        clickResource("label_button")
        assertTrue("Matching passcodes did not create the wallet", waitText("TOS", 300_000))
        assertTrue(runBlocking { GlobalContext.get().get<AccountRepository>().getWallets().isNotEmpty() })
        assertTrue(runBlocking { GlobalContext.get().get<PasscodeManager>().hasPinCode() })

        runBlocking {
            GlobalContext.get().get<AccountRepository>().logout()
            GlobalContext.get().get<PasscodeManager>().reset()
        }
    }

    @Test
    fun deterministicFundedWalletFixtureReachesHomeAndPersists() {
        val accountRepository = GlobalContext.get().get<AccountRepository>()
        val passcodeManager = GlobalContext.get().get<PasscodeManager>()
        val wallet = runBlocking {
            passcodeManager.save("1234")
            val existing = accountRepository.getWallets().firstOrNull()
            existing ?: accountRepository.importWallet(
                ids = listOf("v1-acceptance-wallet"),
                label = Wallet.NewLabel(listOf("V1 Test Wallet"), "⭐", 0xfff5b800.toInt()),
                mnemonic = FIXTURE_MNEMONIC.split(" "),
                versions = listOf(WalletVersion.V5R1),
                testnet = false,
                initialized = listOf(true),
            ).single().also {
                accountRepository.setSelectedWallet(it.id)
            }
        }

        launch()
        if (waitText("Enter passcode", 3_000)) enterPin("1234")
        assertFalse(waitText("Create new wallet", 2_000))
        assertTrue("Native TOS home did not render", waitText("TOS", 30_000) || hasTextContaining("TOS"))
        assertEquals(FIXTURE_RAW_ADDRESS, wallet.accountId)
        assertEquals(FIXTURE_ADDRESS, wallet.address)
        assertNoReachableDeferredCopy()
        assertNoFatalCrash()
    }

    @Test
    fun persistedWalletColdLaunchShowsExactNativeBalanceAndAddress() {
        val wallet = currentWallet()
        val nodeBalance = GlobalContext.get().get<API>()
            .getTosBalance(wallet.accountId, wallet.testnet, "USD")
        assertNotNull("Local TOS node did not return the fixture balance", nodeBalance)
        val expectedBalance = CurrencyFormatter.format(value = nodeBalance!!.value).toString()

        launch()
        assertFalse(waitText("Create new wallet", 2_000))
        assertTrue(waitText("TOS", 30_000))
        assertTrue("UI did not render exact local-node balance $expectedBalance", waitTextContaining(expectedBalance, 30_000))
        assertTrue(waitTextContaining("UQCJ", 10_000))
        assertNoReachableDeferredCopy()
        assertNoFatalCrash()
    }

    @Test
    fun walletWindowProtectsSensitiveContentAcrossBackgroundAndForeground() {
        launch()
        assertTrue(waitText("TOS", 30_000))
        assertSecureWalletWindow()
        device.pressHome()
        SystemClock.sleep(500)
        launch()
        assertTrue(waitText("TOS", 30_000))
        assertSecureWalletWindow()
        assertNoFatalCrash()
    }

    @Test
    fun launchMemoryAndRepeatedNavigationStayWithinBudgets() {
        val started = SystemClock.elapsedRealtime()
        launch()
        assertTrue(waitText("TOS", 30_000))
        val launchMillis = SystemClock.elapsedRealtime() - started
        assertTrue("Wallet launch exceeded 30 seconds: ${launchMillis}ms", launchMillis <= 30_000)

        val meminfo = device.executeShellCommand("dumpsys meminfo $APP_ID")
        val pssKb = Regex("TOTAL PSS:\\s*(\\d+)").find(meminfo)?.groupValues?.get(1)?.toLongOrNull()
            ?: Regex("^\\s*TOTAL\\s+(\\d+)", RegexOption.MULTILINE).find(meminfo)?.groupValues?.get(1)?.toLongOrNull()
        assertNotNull("Could not read wallet PSS", pssKb)
        assertTrue("Wallet PSS exceeds 512 MiB: ${pssKb}KiB", pssKb!! < 512 * 1024)

        val exerciseStarted = SystemClock.elapsedRealtime()
        repeat(5) {
            val refresh = device.wait(Until.findObject(By.res(APP_ID, "refresh")), 10_000)
            assertNotNull(refresh)
            refresh.swipe(androidx.test.uiautomator.Direction.DOWN, 0.5f)
            SystemClock.sleep(500)
        }
        repeat(3) {
            clickText("Send")
            assertTrue(waitResource("address", 10_000))
            device.pressBack()
            assertTrue(waitText("TOS", 10_000))
        }
        val exerciseMillis = SystemClock.elapsedRealtime() - exerciseStarted
        assertTrue("Repeated refresh/send navigation exceeded 60 seconds: ${exerciseMillis}ms", exerciseMillis <= 60_000)
        assertNoFatalCrash()
    }

    @Test
    fun rpcSettingValidatesPersistsAndRoutesToSecondLocalValidator() {
        val api = GlobalContext.get().get<API>()
        assertEquals(SECOND_LOCAL_RPC, api.setCustomTosRpcEndpoint(" 10.0.2.2:18546/jsonRPC/ "))
        assertEquals(SECOND_LOCAL_RPC, api.customTosRpcEndpoint)
        assertTrue(runCatching { api.setCustomTosRpcEndpoint("file:///not-an-rpc") }.isFailure)
        assertNotNull(api.getTosBalance(currentWallet().accountId, false, "USD"))

        launch()
        clickResource("settings")
        assertTrue(waitText("Settings"))
        assertTrue(waitText(SECOND_LOCAL_RPC))
    }

    @Test
    fun persistedRpcSettingSurvivesColdProcessAndResets() {
        val api = GlobalContext.get().get<API>()
        assertEquals(SECOND_LOCAL_RPC, api.customTosRpcEndpoint)
        assertNotNull(api.getTosBalance(currentWallet().accountId, false, "USD"))
        api.resetCustomTosRpcEndpoint()
        assertEquals(null, api.customTosRpcEndpoint)
        assertTrue(api.tosRpcEndpoint(false).contains("10.0.2.2:18545"))

        launch()
        clickResource("settings")
        assertTrue(waitText("Settings"))
        assertTrue(waitTextContaining("10.0.2.2:18545", 10_000))
    }

    @Test
    fun localTransferRefreshesNativeBalance() {
        val api = GlobalContext.get().get<API>()
        val wallet = currentWallet()
        val before = api.getTosBalance(wallet.accountId, false, "USD")!!.value
        launch()
        assertTrue(waitTextContaining(CurrencyFormatter.format(value = before).toString(), 30_000))

        val connection = URL("http://10.0.2.2:18745/transfer").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        connection.connectTimeout = 5_000
        connection.readTimeout = 70_000
        connection.outputStream.use {
            it.write("""{"address":"${wallet.address}","amount":1}""".encodeToByteArray())
        }
        assertEquals(200, connection.responseCode)
        connection.inputStream.close()

        val deadline = SystemClock.elapsedRealtime() + 45_000
        var after = before
        while (after <= before && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(1_000)
            after = api.getTosBalance(wallet.accountId, false, "USD")!!.value
        }
        assertTrue("Local transfer did not change the node balance", after > before)
        val refresh = device.wait(Until.findObject(By.res(APP_ID, "refresh")), 10_000)
        assertNotNull(refresh)
        refresh.swipe(androidx.test.uiautomator.Direction.DOWN, 0.8f)
        assertTrue(
            "Pull-to-refresh did not render the new local-node balance",
            waitTextContaining(CurrencyFormatter.format(value = after).toString(), 30_000),
        )
    }

    @Test
    fun offlineHistoryShowsErrorAndRetryReconnects() {
        val api = GlobalContext.get().get<API>()
        api.setCustomTosRpcEndpoint("http://10.0.2.2:1")
        try {
            launch()
            clickText("History")
            assertTrue("Offline history did not expose a recoverable error", waitText("Unknown error", 30_000))
            assertTrue(waitText("Retry", 5_000))
            api.resetCustomTosRpcEndpoint()
            clickText("Retry")
            assertTrue("History did not recover after reconnect", waitText("Today", 30_000))
            assertFalse(hasText("Unknown error"))
        } finally {
            api.resetCustomTosRpcEndpoint()
        }
        assertNoFatalCrash()
    }

    @Test
    fun receiveCopiesSharesAndEncodesExactNativeTosAddress() {
        launch()
        assertTrue(waitText("Receive", 30_000))
        clickText("Receive")
        assertTrue(waitText("Receive TOS", 10_000))
        assertTrue(waitText(FIXTURE_ADDRESS))
        val qrPayload = "tos://transfer/$FIXTURE_ADDRESS"
        assertEquals(qrPayload, QRScreen(currentWallet()).getQrContent(FIXTURE_ADDRESS, TokenEntity.TON))
        assertEquals(qrPayload, decodeQr(QR.Builder(qrPayload).setSize(512).build()))
        assertEquals(FIXTURE_ADDRESS, QRScreen.shareIntent(FIXTURE_ADDRESS).getStringExtra(Intent.EXTRA_TEXT))

        clickText("Copy")
        val clipboard = instrumentation.targetContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(FIXTURE_ADDRESS, clipboard.primaryClip?.getItemAt(0)?.coerceToText(instrumentation.targetContext)?.toString())

        val share = device.wait(Until.findObject(By.desc("Share")), 5_000)
        assertNotNull("Receive share control is missing an accessible label", share)
        share.click()
        SystemClock.sleep(500)
        assertNoFatalCrash()
        device.pressBack()
    }

    @Test
    fun walletSendAndSettingsExposeOnlyNativeV1Controls() {
        launch()
        clickText("Send")
        assertTrue(waitText("Address or name", 10_000))
        for (label in listOf("TOS", "Continue")) {
            assertTrue("Missing native send control: $label", waitText(label))
        }
        for (id in listOf("amount", "comment", "button")) {
            assertTrue("Missing native send control: $id", device.hasObject(By.res(APP_ID, id)))
        }
        assertNoReachableDeferredCopy()
        launch()

        clickResource("settings")
        assertTrue(waitText("Settings"))
        for (label in listOf("Backup", "Security", "Currency", "RPC Node", "Language", "Appearance", "Legal")) {
            assertTrue("Missing retained V1 setting: $label", waitText(label))
        }
        for (label in listOf("Search", "Connected Apps", "Widget", "Wallet v4R2", "Wallet W5", "Battery")) {
            assertFalse("Deferred setting is reachable: $label", hasText(label))
        }
        assertNoReachableDeferredCopy()
        assertNoFatalCrash()
    }

    @Test
    fun sendValidationConfirmationAndCancelDoNotBroadcast() {
        val api = GlobalContext.get().get<API>()
        val wallet = currentWallet()
        val beforeSeqno = api.getAccountSeqno(wallet.accountId, wallet.testnet)

        launch()
        clickText("Send")
        assertTrue(waitResource("address", 10_000))
        val textInputs = device.findObjects(By.res(APP_ID, "input_field"))
        assertTrue("Address/comment input fields are missing", textInputs.size >= 2)
        val address = textInputs.first()
        val amount = device.wait(Until.findObject(By.res(APP_ID, "coin_input")), 60_000)
        val comment = textInputs.last()
        val continueButton = device.wait(Until.findObject(By.res(APP_ID, "button")), 10_000)
        assertNotNull("Native amount input did not finish loading", amount)
        assertNotNull(continueButton)

        val clipboard = instrumentation.targetContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("TOS recipient", RECIPIENT_ADDRESS))
        clickResource("paste")
        assertEquals("Paste did not preserve the exact native address", RECIPIENT_ADDRESS, address.text)

        address.text = "not-an-address"
        amount.text = "1"
        SystemClock.sleep(1_500)
        assertFalse("Invalid address enabled Continue", continueButton.isEnabled)

        address.text = RECIPIENT_ADDRESS
        amount.text = "0"
        SystemClock.sleep(1_500)
        assertFalse("Zero amount enabled Continue", continueButton.isEnabled)
        amount.text = "999999"
        SystemClock.sleep(1_500)
        assertFalse("Over-balance amount enabled Continue", continueButton.isEnabled)

        address.text = RECIPIENT_ADDRESS
        amount.text = "1"
        assertTrue("Whole native amount did not enable Continue", waitEnabled("button", 30_000))
        for (invalidAmount in listOf("-1", "0.0000000001", "999999999999999999999999999999999999")) {
            amount.text = invalidAmount
            SystemClock.sleep(1_500)
            assertFalse("Invalid native amount enabled Continue: $invalidAmount", continueButton.isEnabled)
        }

        amount.text = "0.01"
        comment.text = UNICODE_COMMENT
        assertTrue("Valid native transfer did not enable Continue", waitEnabled("button", 30_000))
        continueButton.click()
        assertTrue("Confirmation did not open", waitResource("review_title", 30_000))
        assertTrue(waitTextContaining("0.01", 10_000))
        assertTrue(waitTextContaining(UNICODE_COMMENT, 10_000))
        assertTrue(device.hasObject(By.res(APP_ID, "review_recipient_address")))
        assertTrue(device.hasObject(By.res(APP_ID, "review_fee")))

        device.pressBack()
        launch()
        val afterSeqno = api.getAccountSeqno(wallet.accountId, wallet.testnet)
        assertEquals("Cancelling confirmation broadcast a transaction", beforeSeqno, afterSeqno)
        assertNoFatalCrash()
    }

    @Test
    fun rapidRecipientReplacementKeepsOnlyLatestResolvedState() {
        launch()
        clickText("Send")
        assertTrue(waitResource("address", 10_000))
        val address = device.findObjects(By.res(APP_ID, "input_field")).first()
        val amount = device.wait(Until.findObject(By.res(APP_ID, "coin_input")), 60_000)
        assertNotNull("Native amount input did not finish loading", amount)

        address.text = "not-an-address"
        address.text = RECIPIENT_ADDRESS
        amount.text = "0.01"

        assertTrue("Latest valid recipient did not enable Continue", waitEnabled("button", 30_000))
        assertEquals("A stale recipient request replaced the latest input", RECIPIENT_ADDRESS, address.text)
        assertFalse("A stale recipient error remained visible", hasText("Invalid wallet address."))
        assertFalse("Recipient validation polluted global history state", hasText("Unknown error"))
        assertNoFatalCrash()
    }

    @Test
    fun nativeTransferSignsBroadcastsAndRoundTripsUnicodeComment() {
        val api = GlobalContext.get().get<API>()
        val wallet = currentWallet()
        val beforeSeqno = api.getAccountSeqno(wallet.accountId, wallet.testnet)

        launch()
        clickText("Send")
        assertTrue(waitResource("address", 10_000))
        val textInputs = device.findObjects(By.res(APP_ID, "input_field"))
        assertTrue("Address/comment input fields are missing", textInputs.size >= 2)
        textInputs.first().text = RECIPIENT_ADDRESS
        val amount = device.wait(Until.findObject(By.res(APP_ID, "coin_input")), 60_000)
        assertNotNull("Native amount input did not finish loading", amount)
        amount.text = "0.01"
        textInputs.last().text = UNICODE_COMMENT
        assertTrue("Valid transfer did not enable Continue", waitEnabled("button", 30_000))
        clickResource("button")

        assertTrue("Confirmation did not open", waitResource("review_title", 30_000))
        assertTrue("Confirmation lost exact amount", waitTextContaining("0.01 TOS", 10_000))
        assertTrue("Confirmation lost exact Unicode comment", waitTextContaining(UNICODE_COMMENT, 10_000))
        assertTrue("Confirmation lost exact normalized recipient", waitText(RECIPIENT_ADDRESS, 10_000))
        val fee = device.wait(Until.findObject(By.res(APP_ID, "review_fee")), 30_000)
        assertNotNull("Confirmation fee is missing", fee)
        assertTrue("Confirmation fee was not resolved", waitEnabled("confirm_button", 60_000))
        fun descendantText(node: androidx.test.uiautomator.UiObject2): String =
            (listOf(node.text.orEmpty()) + node.children.map(::descendantText)).joinToString(" ")
        val feeText = descendantText(fee)
        assertTrue("Confirmation fee is empty or unknown: $feeText", feeText.any(Char::isDigit) && !feeText.contains("unknown", true))

        clickResource("confirm_button")
        assertTrue("Signing did not request passcode", waitText("Enter passcode", 15_000))
        enterPin("1234")

        val deadline = SystemClock.elapsedRealtime() + 90_000
        var afterSeqno = beforeSeqno
        while (SystemClock.elapsedRealtime() < deadline && afterSeqno <= beforeSeqno) {
            SystemClock.sleep(1_000)
            afterSeqno = api.getAccountSeqno(wallet.accountId, wallet.testnet)
        }
        assertEquals("Exactly one native transaction must be broadcast", beforeSeqno + 1, afterSeqno)

        var commentRoundTripped = false
        while (SystemClock.elapsedRealtime() < deadline && !commentRoundTripped) {
            commentRoundTripped = api.fetchTosNativeTransfers(wallet.accountId, wallet.testnet)
                .any { it.comment == UNICODE_COMMENT && it.amount == 10_000_000L }
            if (!commentRoundTripped) SystemClock.sleep(1_000)
        }
        assertTrue("Unicode comment and exact native amount did not round-trip through local TOS history", commentRoundTripped)

        launch()
        clickText("History")
        assertTrue("Broadcast transfer did not appear in History", waitTextContaining(UNICODE_COMMENT, 30_000))
        assertTrue("History does not identify the native asset", waitTextContaining("TOS", 10_000))
        assertNoFatalCrash()
    }

    @Test
    fun maxNativeTransferCarriesBalanceWhileReservingNetworkFee() {
        val api = GlobalContext.get().get<API>()
        val wallet = currentWallet()
        val sourceBefore = api.getTosBalance(wallet.accountId, wallet.testnet, "USD")!!.value.toBigInteger()
        val recipientBefore = api.getTosBalance(RECIPIENT_ADDRESS, wallet.testnet, "USD")!!.value.toBigInteger()
        val seqnoBefore = api.getAccountSeqno(wallet.accountId, wallet.testnet)
        assertTrue("Funded fixture is empty before send-all", sourceBefore.signum() > 0)

        launch()
        clickText("Send")
        assertTrue(waitResource("address", 10_000))
        device.findObjects(By.res(APP_ID, "input_field")).first().text = RECIPIENT_ADDRESS
        clickResource("max")
        assertTrue("MAX did not enable Continue", waitEnabled("button", 30_000))
        clickResource("button")
        assertTrue(waitResource("review_title", 30_000))
        assertTrue("MAX confirmation did not resolve its fee", waitEnabled("confirm_button", 60_000))
        clickResource("confirm_button")
        assertTrue("Send-all warning was not shown", waitTextContaining("all your balance", 10_000))
        clickText("Continue")
        assertTrue("Signing did not request passcode", waitText("Enter passcode", 15_000))
        enterPin("1234")

        val deadline = SystemClock.elapsedRealtime() + 90_000
        var seqnoAfter = seqnoBefore
        while (SystemClock.elapsedRealtime() < deadline && seqnoAfter <= seqnoBefore) {
            SystemClock.sleep(1_000)
            seqnoAfter = api.getAccountSeqno(wallet.accountId, wallet.testnet)
        }
        assertEquals("Send-all must broadcast exactly once", seqnoBefore + 1, seqnoAfter)
        var sourceAfter = sourceBefore
        var recipientAfter = recipientBefore
        while (SystemClock.elapsedRealtime() < deadline && sourceAfter >= sourceBefore) {
            SystemClock.sleep(1_000)
            sourceAfter = api.getTosBalance(wallet.accountId, wallet.testnet, "USD")!!.value.toBigInteger()
            recipientAfter = api.getTosBalance(RECIPIENT_ADDRESS, wallet.testnet, "USD")!!.value.toBigInteger()
        }
        val delivered = recipientAfter - recipientBefore
        val reservedFee = sourceBefore - delivered - sourceAfter
        assertTrue("MAX did not carry the available native balance", delivered.signum() > 0)
        assertTrue("MAX failed to reserve a positive network fee", reservedFee.signum() > 0)
        assertTrue("MAX recipient received the pre-fee balance", delivered < sourceBefore)
        assertNoFatalCrash()
    }

    @Test
    fun nativeHistoryDetailsShowExactChainFields() {
        val api = GlobalContext.get().get<API>()
        val wallet = currentWallet()
        val transfer = api.fetchTosNativeTransfers(wallet.accountId, wallet.testnet)
            .firstOrNull { it.comment == UNICODE_COMMENT }
        assertNotNull("The local-chain Unicode transfer fixture is missing", transfer)
        transfer!!
        assertTrue("Local node did not return the transaction fee", transfer.fee.signum() > 0)

        launch()
        clickText("History")
        assertTrue("Transfer is missing from history", waitText(UNICODE_COMMENT, 30_000))
        clickText(UNICODE_COMMENT)
        val expectedDate = DateHelper.formatTransactionDetailsTime(transfer.timestamp * 1_000, Locale.US)
        assertTrue("Details lost the exact chain timestamp", waitText("Sent $expectedDate", 10_000))
        assertTrue("Details lost the exact native amount/symbol", waitTextContaining("0.01 TOS", 10_000))
        assertTrue("Details lost the normalized recipient", waitText(NORMALIZED_RECIPIENT_ADDRESS, 10_000))
        assertTrue("Details lost the exact Unicode comment", waitText(UNICODE_COMMENT, 10_000))
        val expectedFee = CurrencyFormatter.format("TON", Coins.ofNano(transfer.fee.toString()))
            .withCustomSymbol(instrumentation.targetContext)
        assertTrue("Details lost the exact node fee: $expectedFee", waitTextContaining(expectedFee.toString(), 10_000))
        assertNoReachableDeferredCopy()
        assertNoFatalCrash()
    }

    @Test
    fun timeoutRetryBroadcastsOnlyOnceAndRelaunchReconcilesHistory() {
        val api = GlobalContext.get().get<API>()
        val wallet = currentWallet()
        val funding = URL("http://10.0.2.2:18745/transfer").openConnection() as HttpURLConnection
        funding.requestMethod = "POST"
        funding.doOutput = true
        funding.setRequestProperty("Content-Type", "application/json")
        funding.outputStream.use {
            it.write("{\"address\":\"$FIXTURE_ADDRESS\",\"amount\":1}".toByteArray())
        }
        assertEquals(200, funding.responseCode)
        funding.inputStream.close()
        val fundDeadline = SystemClock.elapsedRealtime() + 45_000
        while (SystemClock.elapsedRealtime() < fundDeadline &&
            api.getTosBalance(wallet.accountId, false, "USD")!!.value.toBigInteger().signum() == 0) {
            SystemClock.sleep(1_000)
        }

        val beforeSeqno = api.getAccountSeqno(wallet.accountId, wallet.testnet)
        val retryComment = "$RETRY_COMMENT-$beforeSeqno"
        api.setCustomTosRpcEndpoint("http://10.0.2.2:18746")
        try {
            launch()
            clickText("Send")
            assertTrue(waitResource("address", 10_000))
            val inputs = device.findObjects(By.res(APP_ID, "input_field"))
            inputs.first().text = RECIPIENT_ADDRESS
            device.findObject(By.res(APP_ID, "coin_input")).text = "0.005"
            inputs.last().text = retryComment
            assertTrue(waitEnabled("button", 30_000))
            clickResource("button")
            assertTrue(waitEnabled("confirm_button", 60_000))
            clickResource("confirm_button")
            assertTrue(waitText("Enter passcode", 15_000))
            enterPin("1234")

            val deadline = SystemClock.elapsedRealtime() + 90_000
            var afterSeqno = beforeSeqno
            while (SystemClock.elapsedRealtime() < deadline && afterSeqno <= beforeSeqno) {
                SystemClock.sleep(1_000)
                afterSeqno = api.getAccountSeqno(wallet.accountId, wallet.testnet)
            }
            assertEquals("Dropped response/retry duplicated the transfer", beforeSeqno + 1, afterSeqno)
            val stats = URL("http://10.0.2.2:18746/stats").readText()
            assertTrue("Fault proxy did not inject the accepted-response timeout: $stats", stats.contains("\"send_calls\": 1") && stats.contains("\"dropped\": 1"))
            assertEquals("Retrying the same signed transfer changed seqno twice", beforeSeqno + 1, api.getAccountSeqno(wallet.accountId, wallet.testnet))

            launch()
            clickText("History")
            assertTrue("Relaunch did not reconcile the accepted transfer", waitText(retryComment, 30_000))
            val matching = api.fetchTosNativeTransfers(wallet.accountId, wallet.testnet)
                .count { it.comment == retryComment }
            assertEquals("Reconciled history contains a duplicate transfer", 1, matching)
        } finally {
            api.resetCustomTosRpcEndpoint()
        }
        assertNoFatalCrash()
    }

    @Test
    fun passcodeThrottleKeystoreAndRuntimeSecretPolicyHold() {
        val context = instrumentation.targetContext
        val passcodeManager = GlobalContext.get().get<PasscodeManager>()
        val store = PasscodeStore(context)
        runBlocking {
            assertTrue(passcodeManager.isValid(context, "1234"))
            assertFalse(passcodeManager.isValid(context, "9999"))
            repeat(5) { assertFalse(store.compare("9999")) }
            assertTrue("Six failed attempts must start the lockout", store.lockoutSecondsRemaining() > 0)
            assertFalse(store.compare("1234"))
            store.setPinCode("1234")
            assertEquals(0, store.lockoutSecondsRemaining())
            assertTrue(store.compare("1234"))
        }

        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        for (alias in listOf(
            "_network_tos_account_master_key_",
            "_network_tos_vault_master_key_",
            "_network_tos_passcode_master_key_",
        )) {
            assertTrue("Missing TOS Keystore key: $alias", keyStore.containsAlias(alias))
        }

        val logs = device.executeShellCommand("logcat -d -t 2000")
        assertFalse("Recovery phrase leaked to runtime logs", logs.contains(FIXTURE_MNEMONIC))
        assertFalse("Passcode leaked to runtime logs", logs.contains("1234"))
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipboardText = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        assertFalse("Recovery phrase leaked to clipboard", clipboardText.contains(FIXTURE_MNEMONIC))
    }

    @Test
    fun recoveryPhraseRequiresCorrectPasscode() {
        launch()
        clickResource("settings")
        assertTrue(waitText("Settings"))
        clickText("Backup")
        assertTrue(waitText("Backup", 10_000))
        if (waitText("Show recovery phrase", 3_000)) {
            clickText("Show recovery phrase")
        } else {
            clickText("Back Up Manually")
        }
        assertTrue(waitText("Attention", 10_000))
        clickResource("continue_button")
        assertTrue(waitText("Enter passcode", 10_000))
        assertFalse(hasText("mansion"))

        enterPin("9999")
        SystemClock.sleep(1_500)
        assertTrue("Wrong passcode dismissed the authentication gate", hasText("Enter passcode"))
        assertFalse("Wrong passcode exposed the recovery phrase", hasText("mansion"))
        enterPin("1234")
        assertTrue(waitText("Your recovery phrase", 15_000))
        for (word in listOf("mansion", "chef", "voice")) {
            assertTrue("Authenticated phrase is missing word: $word", waitTextContaining(word, 5_000))
        }
        assertSecureWalletWindow()
        assertNoFatalCrash()
    }

    @Test
    fun fundedHistoryLoadsAndPaginatesWithoutDuplicateTransactions() {
        launch()
        clickText("History")
        assertTrue("Funded history did not render", waitText("Today", 30_000))
        assertFalse("History rendered an RPC error", hasText("Unknown error"))
        assertFalse("History rendered retry instead of transactions", hasText("Retry"))
        val history = device.wait(Until.findObject(By.scrollable(true)), 10_000)
        assertNotNull("History list is not scrollable", history)
        history.swipe(androidx.test.uiautomator.Direction.UP, 0.8f)
        SystemClock.sleep(3_000)
        assertFalse("Pagination rendered an RPC error", hasText("Unknown error"))
        assertFalse("Pagination rendered retry instead of transactions", hasText("Retry"))
        assertNoFatalCrash()
    }

    @Test
    fun deferredDeepLinksCannotOpenProductScreens() {
        for (route in listOf("staking", "battery", "browser", "exchange", "swap", "collectibles", "jetton")) {
            launch(Intent.ACTION_VIEW, "tos://$route")
            SystemClock.sleep(350)
            assertFalse("Deferred route became reachable: $route", hasText(route, ignoreCase = true))
        }
    }

    @Test
    fun backgroundAndForegroundDoNotExposeOrCrashOnboarding() {
        launch()
        assertTrue(waitText("TOS Wallet"))
        device.pressHome()
        SystemClock.sleep(500)
        launch()
        assertTrue(waitText("TOS Wallet"))
        assertNoFatalCrash()
    }

    @Test
    fun onboardingControlsExposeAccessibleNames() {
        launch()
        val controls = device.findObjects(By.clickable(true).focusable(true).pkg(APP_ID))
        assertTrue("No reachable controls found", controls.isNotEmpty())
        controls.forEach { control ->
            val named = !control.text.isNullOrBlank() || !control.contentDescription.isNullOrBlank()
            assertTrue("Unnamed clickable control: ${control.resourceName}", named)
        }
    }

    @Test
    fun retainedWalletControlsExposeAccessibleNames() {
        fun check(screen: String) {
            device.waitForIdle()
            val controls = device.findObjects(By.clickable(true).pkg(APP_ID))
            val namedBounds = device.findObjects(By.pkg(APP_ID)).mapNotNull { node ->
                runCatching {
                    if (!node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()) node.visibleBounds else null
                }.getOrNull()
            }
            assertTrue("No controls found on $screen", controls.isNotEmpty())
            val unnamed = controls.mapNotNull { control ->
                runCatching {
                    fun hasName(node: androidx.test.uiautomator.UiObject2): Boolean =
                        !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank() || node.children.any(::hasName)
                    if (hasName(control)) null else control.resourceName to control.visibleBounds
                }.getOrNull()
            }.filter { (_, bounds) ->
                val associatedLabel = namedBounds.any { label ->
                    val center = label.centerX() to label.centerY()
                    bounds.contains(center.first, center.second)
                }
                !associatedLabel
            }
            assertTrue("Unnamed clickable controls on $screen: ${unnamed.joinToString()}", unnamed.isEmpty())
        }

        launch(); check("wallet")
        clickText("Send"); assertTrue(waitText("Address or name")); check("send")
        launch(); clickText("Receive"); assertTrue(waitText("Receive TOS")); check("receive")
        launch(); clickText("History"); assertTrue(waitText("Today", 30_000)); check("history")
        launch(); clickResource("settings"); assertTrue(waitText("Settings")); check("settings")
        for (entry in listOf("Backup", "Security", "Currency", "RPC Node", "Language", "Appearance", "Legal")) {
            launch()
            assertTrue("Wallet did not settle before opening $entry", waitResource("settings", 30_000))
            clickResource("settings")
            assertTrue("Settings did not open before $entry", waitText("Settings", 30_000))
            clickText(entry)
            SystemClock.sleep(500)
            check(entry)
        }
        assertNoFatalCrash()
    }

    @Test
    fun configuredOnboardingDoesNotClipOrCrash() {
        launch()
        assertTrue(device.hasObject(By.pkg(APP_ID)))
        val width = device.displayWidth
        val height = device.displayHeight
        val controls = device.findObjects(By.clickable(true).pkg(APP_ID))
        assertTrue("Configured onboarding has no controls", controls.isNotEmpty())
        controls.forEach { control ->
            val bounds = control.visibleBounds
            assertTrue("Control has no visible area: ${control.resourceName}", bounds.width() > 0 && bounds.height() > 0)
            assertTrue("Control clips horizontally: $bounds", bounds.left >= 0 && bounds.right <= width)
            assertTrue("Control clips vertically: $bounds", bounds.top >= 0 && bounds.bottom <= height)
        }
        assertNoFatalCrash()
    }

    @Test
    fun sodiumSecretBoxRoundTripsOnAndroidAbi() {
        val plain = "TOS native JNI round trip".encodeToByteArray()
        val nonce = ByteArray(24) { it.toByte() }
        val key = ByteArray(32) { (it + 1).toByte() }
        val encrypted = Sodium.cryptoSecretbox(plain, nonce, key)

        assertNotNull(encrypted)
        assertFalse(plain.contentEquals(encrypted))
        assertArrayEquals(plain, Sodium.cryptoSecretboxOpen(encrypted!!, nonce, key))
    }

    @Test
    fun nativeTosFormattingCoversZeroFractionsAndMaximum() {
        assertEquals("0 TOS", CurrencyFormatter.format("TOS", Coins.ZERO).toString())
        assertEquals("0.5 TOS", CurrencyFormatter.format("TOS", Coins.of("0.5")).toString())
        assertEquals("0.000000001 TOS", CurrencyFormatter.format("TOS", Coins.of("0.000000001")).toString())
        assertEquals(
            "9,223,372,036 TOS",
            CurrencyFormatter.format("TOS", Coins.ofNano(Long.MAX_VALUE.toString())).toString(),
        )
    }

    @Test
    fun signOutRequiresConfirmationAndReturnsToCleanOnboarding() {
        val accountRepository = GlobalContext.get().get<AccountRepository>()
        assertTrue(runBlocking { accountRepository.getWallets().isNotEmpty() })
        launch()
        clickResource("settings")
        assertTrue(waitText("Settings"))
        clickText("Sign out ⭐ V1 Test Wallet")
        assertTrue(waitText("Sign out"))
        val logout = device.wait(Until.findObject(By.res(APP_ID, "logout")), 10_000)
        assertNotNull(logout)
        assertFalse("Sign out must be disabled before explicit confirmation", logout.isEnabled)
        clickResource("confirmation")
        assertTrue("Confirmation did not enable sign out", logout.isEnabled)
        logout.click()
        assertTrue("Clean onboarding did not appear after deletion", waitText("Create new wallet", 30_000))
        assertTrue(runBlocking { accountRepository.getWallets().isEmpty() })
        assertNoFatalCrash()
    }

    @Test
    fun unfundedGeneratedWalletRendersZeroBalanceAndEmptyHistory() {
        val accountRepository = GlobalContext.get().get<AccountRepository>()
        val passcodeManager = GlobalContext.get().get<PasscodeManager>()
        val wallet = runBlocking {
            if (!passcodeManager.hasPinCode()) passcodeManager.save("1234")
            val mnemonic = Mnemonic.generate()
            accountRepository.importWallet(
                ids = listOf("v1-zero-wallet"),
                label = Wallet.NewLabel(listOf("Zero Wallet"), "✨", 0xfff5b800.toInt()),
                mnemonic = mnemonic,
                versions = listOf(WalletVersion.V5R1),
                testnet = false,
                initialized = listOf(false),
            ).single().also { accountRepository.setSelectedWallet(it.id) }
        }
        val balance = GlobalContext.get().get<API>().getTosBalance(wallet.accountId, false, "USD")
        assertNotNull(balance)
        assertTrue(balance!!.value.isZero)

        launch()
        assertTrue(waitText("TOS", 30_000))
        assertTrue(waitText("0", 30_000))
        clickText("History")
        assertTrue(waitText("Your activity will be shown here", 30_000))
        assertTrue(waitText("Make your first transaction!", 10_000))
        assertNoFatalCrash()

        runBlocking {
            accountRepository.logout()
            passcodeManager.reset()
        }
        launch()
        assertTrue(waitText("Create new wallet", 15_000))
    }

    private fun launchImport() {
        launch()
        clickText("Import existing wallet")
        assertTrue(waitText("Choose from the options below."))
        val importRows = device.findObjects(By.text("Import wallet"))
        assertTrue(importRows.isNotEmpty())
        importRows.last().click()
        assertTrue(waitText("Enter recovery phrase"))
    }

    private fun setPhrase(phrase: String) {
        pastePhrase(phrase)
        assertTrue(waitText("Continue", 5_000))
    }

    private fun pastePhrase(phrase: String) {
        val first = device.wait(Until.findObject(By.res(APP_ID, "word_1")), 5_000)
        assertNotNull(first)
        first.text = phrase
    }

    private fun enterPin(pin: String) {
        pin.forEach { digit -> clickText(digit.toString()) }
    }

    private fun assertRejected(phrase: String) {
        launchImport()
        pastePhrase(phrase)
        if (waitText("Continue", 1_000)) {
            clickText("Continue")
            SystemClock.sleep(1_000)
        }
        assertFalse("Invalid phrase reached passcode setup", hasText("Create passcode"))
        assertTrue("Wallet process disappeared after invalid phrase", device.hasObject(By.pkg(APP_ID)))
    }

    private fun currentWallet() = runBlocking {
        GlobalContext.get().get<AccountRepository>().getWallets().single()
    }

    private fun launch(action: String = Intent.ACTION_MAIN, data: String? = null) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(action).apply {
            component = ComponentName(APP_ID, ROOT_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            if (data != null) this.data = android.net.Uri.parse(data)
        }
        context.startActivity(intent)
        device.wait(Until.hasObject(By.pkg(APP_ID).depth(0)), 10_000)
    }

    private fun clickText(text: String) {
        val target = device.wait(Until.findObject(By.text(text)), 10_000)
        assertNotNull("Missing UI text: $text", target)
        target.click()
    }

    private fun clickResource(id: String) {
        val target = device.wait(Until.findObject(By.res(APP_ID, id)), 10_000)
        assertNotNull("Missing UI resource: $id", target)
        target.click()
    }

    private fun decodeQr(bitmap: Bitmap): String {
        val readable = if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        }
        val quietZone = 48
        val padded = Bitmap.createBitmap(
            readable.width + quietZone * 2,
            readable.height + quietZone * 2,
            Bitmap.Config.ARGB_8888,
        )
        Canvas(padded).apply {
            drawColor(Color.WHITE)
            drawBitmap(readable, quietZone.toFloat(), quietZone.toFloat(), null)
        }
        val pixels = IntArray(padded.width * padded.height)
        padded.getPixels(pixels, 0, padded.width, 0, 0, padded.width, padded.height)
        val source = RGBLuminanceSource(padded.width, padded.height, pixels)
        return MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    }

    private fun waitText(text: String, timeout: Long = 10_000): Boolean =
        device.wait(Until.hasObject(By.text(text)), timeout)

    private fun waitResource(id: String, timeout: Long): Boolean =
        device.wait(Until.hasObject(By.res(APP_ID, id)), timeout)

    private fun waitEnabled(id: String, timeout: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            if (device.findObject(By.res(APP_ID, id))?.isEnabled == true) return true
            SystemClock.sleep(250)
        }
        return false
    }

    private fun hasText(text: String, ignoreCase: Boolean = false): Boolean {
        val pattern = if (ignoreCase) Regex.escape(text).toRegex(RegexOption.IGNORE_CASE).toPattern()
        else Regex.escape(text).toRegex().toPattern()
        return device.hasObject(By.text(pattern))
    }

    private fun hasTextContaining(text: String): Boolean =
        device.hasObject(By.textContains(text))

    private fun waitTextContaining(text: String, timeout: Long): Boolean =
        device.wait(Until.hasObject(By.textContains(text)), timeout)

    private fun assertNoReachableDeferredCopy() {
        val visibleCopy = device.findObjects(By.pkg(APP_ID)).flatMap { node ->
            listOfNotNull(node.text, node.contentDescription)
        }
        val forbidden = listOf(
            Regex("\\bTON\\b", RegexOption.IGNORE_CASE),
            Regex("Tonkeeper", RegexOption.IGNORE_CASE),
            Regex("\\bTRON\\b", RegexOption.IGNORE_CASE),
            Regex("TRC20", RegexOption.IGNORE_CASE),
            Regex("Jetton", RegexOption.IGNORE_CASE),
            Regex("\\bNFTs?\\b", RegexOption.IGNORE_CASE),
            Regex("\\bSwap\\b", RegexOption.IGNORE_CASE),
            Regex("Staking", RegexOption.IGNORE_CASE),
            Regex("Battery", RegexOption.IGNORE_CASE),
            Regex("DApps?", RegexOption.IGNORE_CASE),
            Regex("TonConnect", RegexOption.IGNORE_CASE),
            Regex("Buy|Sell", RegexOption.IGNORE_CASE),
        )
        for (pattern in forbidden) {
            val match = visibleCopy.firstOrNull { pattern.containsMatchIn(it) }
            assertEquals("Forbidden/deferred product copy is reachable: $match", null, match)
        }
    }

    private fun assertNoFatalCrash() {
        val output = device.executeShellCommand("logcat -d -t 500 AndroidRuntime:E *:S")
        assertFalse(output.contains("Process: $APP_ID"))
    }

    private fun assertSecureWalletWindow() {
        val windows = device.executeShellCommand("dumpsys window windows")
        val walletWindow = Regex(
            "Window\\{[^\\n]*network\\.tos\\.wallet/network\\.tos\\.wallet\\.app\\.ui\\.screen\\.root\\.RootActivity[\\s\\S]{0,1200}?fl=([^\\n]+)"
        ).find(windows)
        assertNotNull("Active wallet window was not found", walletWindow)
        assertTrue("Wallet window does not set FLAG_SECURE", walletWindow!!.groupValues[1].contains("SECURE"))
    }

    companion object {
        private const val APP_ID = "network.tos.wallet"
        private const val ROOT_ACTIVITY = "network.tos.wallet.app.ui.screen.root.RootActivity"
        private const val FIXTURE_ADDRESS = "UQCJFahawZUzYka4uzFTeWns-oQNfoa0VNVOAn8e8BJnXPZe"
        private const val FIXTURE_RAW_ADDRESS = "0:8915a85ac195336246b8bb31537969ecfa840d7e86b454d54e027f1ef012675c"
        private const val SECOND_LOCAL_RPC = "http://10.0.2.2:18546"
        private const val RECIPIENT_ADDRESS = "Ef8AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADAU"
        private const val NORMALIZED_RECIPIENT_ADDRESS = "Uf8AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAG3R"
        private const val UNICODE_COMMENT = "TOS V1 测试 🌌"
        private const val RETRY_COMMENT = "TOS retry-once"
        private const val FIXTURE_MNEMONIC = "mansion chef affair ancient announce police snap machine vanish liberty peace tennis effort recall law limit mosquito tornado toward advance vibrant bachelor auction voice"
    }
}
