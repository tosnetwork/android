package network.tos.wallet

import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import network.tos.security.Sodium
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

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

        assertTrue(waitText("Create passcode", 20_000))
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
        assertTrue(waitText("Create passcode", 25_000))
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

    private fun waitText(text: String, timeout: Long = 10_000): Boolean =
        device.wait(Until.hasObject(By.text(text)), timeout)

    private fun hasText(text: String, ignoreCase: Boolean = false): Boolean {
        val pattern = if (ignoreCase) Regex.escape(text).toRegex(RegexOption.IGNORE_CASE).toPattern()
        else Regex.escape(text).toRegex().toPattern()
        return device.hasObject(By.text(pattern))
    }

    private fun hasTextContaining(text: String): Boolean =
        device.hasObject(By.textContains(text))

    private fun assertNoReachableDeferredCopy() {
        for (label in listOf("TRON", "TRC20", "Jetton", "NFT", "Swap", "Staking", "Battery", "DApps", "TonConnect")) {
            assertFalse("Deferred product copy is reachable: $label", hasText(label, ignoreCase = true))
        }
    }

    private fun assertNoFatalCrash() {
        val output = device.executeShellCommand("logcat -d -t 500 AndroidRuntime:E *:S")
        assertFalse(output.contains("Process: $APP_ID"))
    }

    companion object {
        private const val APP_ID = "network.tos.wallet"
        private const val ROOT_ACTIVITY = "network.tos.wallet.app.ui.screen.root.RootActivity"
        private const val FIXTURE_MNEMONIC = "mansion chef affair ancient announce police snap machine vanish liberty peace tennis effort recall law limit mosquito tornado toward advance vibrant bachelor auction voice"
    }
}
