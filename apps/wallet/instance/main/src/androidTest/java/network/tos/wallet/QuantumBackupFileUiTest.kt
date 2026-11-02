package network.tos.wallet

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import network.tos.wallet.app.ui.screen.root.RootActivity
import network.tos.wallet.data.account.AccountRepository
import network.tos.wallet.data.passcode.PasscodeManager
import network.tos.wallet.data.account.pq.QuantumNetworkProfile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File

/** Requires a disposable, cleared installation. All typed input is public test material. */
@RunWith(AndroidJUnit4::class)
class QuantumBackupFileUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    private fun click(text: String) {
        checkNotNull(device.wait(Until.findObject(By.text(text)), 15000)) { "Missing $text" }.click()
    }

    private fun pin() {
        val pad = By.res(context.packageName, "num_pad")
        assertTrue(device.wait(Until.hasObject(pad), 15000))
        for (digit in "1234") {
            val key = checkNotNull(device.wait(Until.findObject(By.text(digit.toString()).enabled(true).hasAncestor(pad)), 10000))
            key.click(); device.waitForIdle(3000)
        }
    }

    private fun password() = onView(withContentDescription("quantum.backup.password"))
    private fun capture(name: String) {
        val captured = device.takeScreenshot(File(context.cacheDir, "$name.png"))
        File(context.cacheDir, "backup-capture-status.txt").appendText("$name=$captured\n")
    }
    private fun enterPublicPassword() {
        password().perform(replaceText("public UI backup password"), closeSoftKeyboard())
            .check(matches(withText("public UI backup password")))
    }
    private fun checkCleared() {
        assertTrue(device.wait(Until.hasObject(By.desc("quantum.backup.password")), 10000))
        password().check(matches(withText("")))
        assertFalse(checkNotNull(device.findObject(By.desc("quantum.backup.review"))).isEnabled)
    }

    @Test fun firstInstallPinPickerCancelAndBackgroundClearWithoutPublishingWallet() {
        ActivityScenario.launch(RootActivity::class.java).use {
            val repository = GlobalContext.get().get<AccountRepository>()
            val passcodes = GlobalContext.get().get<PasscodeManager>()
            assertTrue(runBlocking { repository.getWallets().isEmpty() })
            assertFalse(runBlocking { passcodes.hasPinCode() })
            try {
                click("Import existing wallet")
                val import = device.wait(Until.findObject(By.res(context.packageName, "action_cell_title").text("Import wallet")), 10000)
                assertNotNull(import); import!!.click()
                val word = checkNotNull(device.wait(Until.findObject(By.res(context.packageName, "word_1")), 10000))
                word.text = "enhance"
                assertEquals("enhance", word.text)
                androidx.test.espresso.Espresso.closeSoftKeyboard()
                checkNotNull(device.wait(Until.findObject(By.res(context.packageName, "quantum_backup_file")), 10000)).click()
                pin(); pin()
                checkCleared()
                enterPublicPassword()
                assertFalse(checkNotNull(device.findObject(By.desc("quantum.backup.review"))).isEnabled)
                checkNotNull(device.findObject(By.desc("quantum.backup.choose"))).click()
                assertTrue("System file picker must cover the app", device.wait(Until.hasObject(By.pkg("com.android.documentsui")), 15000))
                capture("backup-system-picker")
                device.pressBack()
                checkCleared()
                enterPublicPassword()
                device.pressHome()
                assertTrue(device.wait(Until.gone(By.pkg(context.packageName)), 10000))
                context.startActivity(Intent(context, RootActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                checkCleared()
                assertNotNull(device.wait(Until.findObject(By.text("Choose your backup again to continue.")), 10000))
                capture("backup-cleared-after-background")
                androidx.test.espresso.Espresso.closeSoftKeyboard()
                repeat(3) {
                    if (!device.hasObject(By.res(context.packageName, "word_1"))) {
                        device.pressBack(); device.waitForIdle(3000)
                    }
                }
                val originalWord = checkNotNull(device.wait(Until.findObject(By.res(context.packageName, "word_1")), 10000))
                assertEquals("", originalWord.text.orEmpty())
                assertTrue(runBlocking { repository.getWallets().isEmpty() })
                assertFalse("Cancelled recovery must not persist the staged PIN", runBlocking { passcodes.hasPinCode() })
            } catch (error: Throwable) {
                // No private material is used by this test; failures retain the actual public fixture screen.
                device.dumpWindowHierarchy(File(context.cacheDir, "backup-ui-failure.xml"))
                capture("backup-ui-failure")
                throw error
            }
        }
    }

    /** Test-only DocumentsProvider serves the original public iOS fixture. */
    @Test fun selectedFileUsesLocalNetworkAndCancellationClearsPassword() {
        ActivityScenario.launch(RootActivity::class.java).use {
            val repository = GlobalContext.get().get<AccountRepository>()
            val passcodes = GlobalContext.get().get<PasscodeManager>()
            assertTrue(runBlocking { repository.getWallets().isEmpty() })
            assertFalse(runBlocking { passcodes.hasPinCode() })
            val profile = QuantumNetworkProfile.effective(context)
            try {
                click("Import existing wallet")
                checkNotNull(device.wait(Until.findObject(By.res(context.packageName, "action_cell_title").text("Import wallet")), 10000)).click()
                checkNotNull(device.wait(Until.findObject(By.res(context.packageName, "quantum_backup_file")), 10000)).click()
                pin(); pin(); checkCleared()
                checkNotNull(device.findObject(By.desc("quantum.backup.choose"))).click()
                assertTrue(device.wait(Until.hasObject(By.pkg("com.android.documentsui")), 15000))
                if (device.wait(Until.findObject(By.text("Public TOS backup fixtures")), 2000) == null) {
                    checkNotNull(device.findObject(By.desc("Show roots"))).click()
                }
                checkNotNull(device.wait(Until.findObject(By.res("android", "title").text("Public TOS backup fixtures")), 10000)).click()
                click("Public-iOS-backup.tosqbk")
                assertTrue(device.wait(Until.hasObject(By.text("Encrypted backup selected. Enter its password to review the original wallet.")), 15000))
                enterPublicPassword()
                assertTrue(checkNotNull(device.findObject(By.desc("quantum.backup.review"))).isEnabled)
                repeat(2) { attempt ->
                    if (attempt > 0) enterPublicPassword()
                    checkNotNull(device.findObject(By.desc("quantum.backup.review"))).click()
                    assertTrue(device.wait(Until.hasObject(By.text("Confirm Recovery Network")), 10000))
                    assertNotNull(device.findObject(By.textContains("Global ID: ${profile.globalId}")))
                    assertNotNull(device.findObject(By.textContains(profile.rootHash)))
                    assertNotNull(device.findObject(By.textContains(profile.fileHash)))
                    if (attempt == 0) checkNotNull(device.findObject(By.res("android", "button2"))).click()
                    else device.pressBack()
                    checkCleared()
                }
                val after = QuantumNetworkProfile.effective(context)
                assertEquals(profile.globalId, after.globalId)
                assertEquals(profile.rootHash, after.rootHash); assertEquals(profile.fileHash, after.fileHash)
                assertTrue(runBlocking { repository.getWallets().isEmpty() })
                assertFalse(runBlocking { passcodes.hasPinCode() })
            } catch (error: Throwable) {
                device.dumpWindowHierarchy(File(context.cacheDir, "backup-file-selection-failure.xml"))
                capture("backup-file-selection-failure")
                throw error
            }
        }
    }
}
