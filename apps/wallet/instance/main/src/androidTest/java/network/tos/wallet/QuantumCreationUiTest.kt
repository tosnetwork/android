package network.tos.wallet

import android.content.ComponentName
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import network.tos.security.Security
import network.tos.security.pq.*
import network.tos.wallet.api.API
import network.tos.wallet.data.account.AccountRepository
import network.tos.wallet.data.account.pq.QuantumCreationRecord
import network.tos.wallet.data.account.pq.QuantumNetworkProfile
import network.tos.wallet.data.passcode.PasscodeManager
import network.tos.wallet.data.passcode.source.PasscodeStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File

/** Run each UI case in a cleared disposable installation. Live creation requires a fresh chain anchor. */
@RunWith(AndroidJUnit4::class)
class QuantumCreationUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val repository get() = GlobalContext.get().get<AccountRepository>()
    private val api get() = GlobalContext.get().get<API>()
    private fun launch() {
        assertTrue(runBlocking { repository.getWallets().isEmpty() })
        context.startActivity(Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName("network.tos.wallet", "network.tos.wallet.app.ui.screen.root.RootActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        assertTrue(device.wait(Until.hasObject(By.text("Create new wallet")), 15000))
    }
    private fun click(text: String) { checkNotNull(device.wait(Until.findObject(By.text(text)), 15000)) { "Missing $text" }.click() }
    private fun pin() { "1234".forEach { click(it.toString()) } }
    private fun resource(id: String) = checkNotNull(device.wait(Until.findObject(By.res("network.tos.wallet", id)), 15000))

    @Test fun trustedConfigurationIsValidatedBeforeCreationAndSurvivesReload() {
        api.setCustomTosRpcEndpoint("http://10.0.2.2:1")
        launch()
        click("Create new wallet")
        assertTrue(device.wait(Until.hasObject(By.text("Trusted Quantum network")), 10000))
        fun field(name: String) = checkNotNull(device.wait(Until.findObject(By.desc(name)), 10000))
        field("Global network ID").text = "2147483648"
        field("Zerostate root hash").text = "0".repeat(64)
        field("Zerostate file hash").text = "cd".repeat(32)
        device.findObject(By.res("android", "button1")).click()
        assertNull(QuantumNetworkProfile.selected(context))
        assertFalse(device.hasObject(By.text("Create passcode")))
        field("Global network ID").text = "-1"
        field("Zerostate root hash").text = "AB".repeat(32)
        device.findObject(By.res("android", "button1")).click()
        val saved = QuantumNetworkProfile.selected(context)!!
        assertEquals(-1, saved.globalId)
        assertEquals("ab".repeat(32), saved.rootHash)
        assertEquals("cd".repeat(32), saved.fileHash)
        click("Create new wallet")
        assertTrue(device.wait(Until.hasObject(By.text("Create passcode")), 15000))
        device.pressBack(); device.pressBack(); device.pressBack()
        assertTrue(runBlocking { repository.getWallets().isEmpty() })
        assertFalse(runBlocking { GlobalContext.get().get<PasscodeManager>().hasPinCode() })
    }

    @Test fun freshNetworkCreatesQuantumFromActualOnboardingWithProtectedCustody() {
        val args = InstrumentationRegistry.getArguments()
        val endpoint = requireNotNull(args.getString("quantumRpc")) { "Fresh Quantum test node required" }
        val profile = QuantumNetworkProfile(requireNotNull(args.getString("quantumGlobalId")).toInt(),
            requireNotNull(args.getString("quantumRootHash")), requireNotNull(args.getString("quantumFileHash")))
        api.setCustomTosRpcEndpoint(endpoint)
        profile.save(context)
        launch()
        click("Create new wallet")
        assertTrue(device.wait(Until.hasObject(By.text("Create passcode")), 15000))
        pin()
        assertTrue(device.wait(Until.hasObject(By.text("Re-enter passcode")), 15000))
        pin()
        assertTrue(device.wait(Until.hasObject(By.text("Customize your Wallet")), 15000))
        resource("input_field").text = "Quantum live creation"
        resource("label_button").click()
        // Creation confirms the saved PIN before touching custody.
        pin()
        val deadline = android.os.SystemClock.elapsedRealtime() + 90000
        var wallets = runBlocking { repository.getWallets() }
        while (wallets.isEmpty() && android.os.SystemClock.elapsedRealtime() < deadline) {
            assertFalse("Creation displayed an error", device.hasObject(By.text("Wallet creation did not finish")))
            android.os.SystemClock.sleep(500)
            wallets = runBlocking { repository.getWallets() }
        }
        val wallet = wallets.single()
        assertTrue("Created wallet did not reach the main screen", device.wait(Until.hasObject(By.text("TOS")), 30000))
        assertEquals(wallet.id, runBlocking { repository.getSelectedWallet() }?.id)
        assertTrue(wallet.isQuantum)
        assertNull(wallet.classicalPublicKey)
        assertNull(runBlocking { repository.getPrivateKey(wallet.id) })
        assertEquals(24, runBlocking { repository.getMnemonic(wallet.id) }!!.size)
        val record = QuantumCreationRecord.decode(File(context.noBackupFilesDir, "quantum-creation/${wallet.id}.json").readBytes())
        assertEquals(wallet.quantumIdentity, record.identity)
        assertEquals(profile.rootHash, record.profile.rootHash)
        File(context.cacheDir, "quantum-live-creation-receipt.json").writeBytes(record.encode())
        val authority = record.identity.wallet.initialAuthority
        val binding = QuantumSeedVault.Context(authority.namespace, authority.globalId, 0, 0)
        val prefs = Security.pref(context, "_network_tos_quantum_creation_key_", "quantum_creation_custody", requireUnlockedDevice = true)
        val vault = QuantumSeedVault(prefs)
        val key = QuantumDeviceKey.get(false)
        assertArrayEquals(authority.primaryKey, vault.publicKey(wallet.id, QuantumRole.PRIMARY, binding, key))
        assertArrayEquals(authority.rescueKey, vault.publicKey(wallet.id, QuantumRole.RESCUE, binding, key))
        assertEquals(wallet, runBlocking { repository.getWalletById(wallet.id) })
        // The main registry and both roles can be removed; unrelated services survive.
        prefs.edit().putString("unrelated-test-marker", "keep").commit()
        runBlocking { repository.delete(wallet) }
        assertFalse(prefs.contains("quantum.mnemonic.v1.${wallet.id}"))
        for (role in QuantumRole.entries) assertFalse(prefs.contains("quantum.seed.v1.${role.id}.${wallet.id}"))
        assertEquals("keep", prefs.getString("unrelated-test-marker", null))
        prefs.edit().remove("unrelated-test-marker").commit()
    }

    @Test fun partialPinRecordRequiresAuthenticationInsteadOfNewPin() = runBlocking {
        val store = PasscodeStore(context)
        store.setPinCode("1234")
        val prefs = Security.pref(context, "_network_tos_passcode_master_key_", "passcode", requireUnlockedDevice = true)
        assertTrue(prefs.edit().remove("code_salt").commit())
        assertTrue(store.hasPinCode)
        assertTrue(GlobalContext.get().get<PasscodeManager>().hasPinCode())
        assertFalse(store.compare("1234"))
    }
}
