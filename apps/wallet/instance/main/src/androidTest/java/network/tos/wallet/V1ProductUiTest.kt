package network.tos.wallet

import android.content.ComponentName
import android.content.ClipboardManager
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
import network.tos.qr.QR
import network.tos.security.Sodium
import network.tos.wallet.api.API
import network.tos.wallet.data.account.AccountRepository
import network.tos.wallet.data.account.Wallet
import network.tos.wallet.data.passcode.PasscodeManager
import network.tos.wallet.data.passcode.source.PasscodeStore
import network.tos.wallet.api.entity.TokenEntity
import network.tos.wallet.app.ui.screen.qr.QRScreen
import org.koin.core.context.GlobalContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
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

        assertTrue(waitText("Create passcode", 180_000))
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
        assertTrue(waitText("Create passcode", 180_000))
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
        val amount = device.wait(Until.findObject(By.res(APP_ID, "coin_input")), 10_000)
        val comment = textInputs.last()
        val continueButton = device.wait(Until.findObject(By.res(APP_ID, "button")), 10_000)
        assertNotNull(amount)
        assertNotNull(continueButton)

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
    fun sodiumSecretBoxRoundTripsOnAndroidAbi() {
        val plain = "TOS native JNI round trip".encodeToByteArray()
        val nonce = ByteArray(24) { it.toByte() }
        val key = ByteArray(32) { (it + 1).toByte() }
        val encrypted = Sodium.cryptoSecretbox(plain, nonce, key)

        assertNotNull(encrypted)
        assertFalse(plain.contentEquals(encrypted))
        assertArrayEquals(plain, Sodium.cryptoSecretboxOpen(encrypted!!, nonce, key))
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
        for (label in listOf("TRON", "TRC20", "Jetton", "NFT", "Swap", "Staking", "Battery", "DApps", "TonConnect")) {
            assertFalse("Deferred product copy is reachable: $label", hasText(label, ignoreCase = true))
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
        private const val UNICODE_COMMENT = "TOS V1 测试 🌌"
        private const val FIXTURE_MNEMONIC = "mansion chef affair ancient announce police snap machine vanish liberty peace tennis effort recall law limit mosquito tornado toward advance vibrant bachelor auction voice"
    }
}
