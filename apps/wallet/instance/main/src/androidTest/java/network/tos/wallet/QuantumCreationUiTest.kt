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
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File

/** Run each UI case in a cleared disposable installation. Live creation requires a fresh chain anchor. */
@RunWith(AndroidJUnit4::class)
class QuantumCreationUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val repository get() = GlobalContext.get().get<AccountRepository>()
    private val api get() = GlobalContext.get().get<API>()
    private fun launch(pending: Boolean = false) {
        if (!pending) assertTrue(runBlocking { repository.getWallets().isEmpty() })
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
        verifyQuantumBalanceAndFailureRecovery(wallet, endpoint)
        verifyFundedQuantumTotals(profile, endpoint)
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

    private fun verifyQuantumBalanceAndFailureRecovery(wallet: network.tos.wallet.data.account.entities.WalletEntity, endpoint: String) {
        val assets = GlobalContext.get().get<network.tos.wallet.app.manager.assets.AssetsManager>()
        val currency = network.tos.wallet.data.core.currency.WalletCurrency.TON
        val balance = runBlocking { repository.readQuantumAccount(wallet) }
        assertTrue(balance.status is network.tos.wallet.data.account.pq.QuantumSelfPayAccount.Status.Absent)
        assertEquals(java.math.BigInteger.ZERO, balance.balance)
        assertEquals(java.math.BigInteger.ZERO, runBlocking { assets.getTokens(wallet, currency, true) }.single().balance.toBigInteger())
        assets.setCachedTotalBalance(wallet, currency, true, network.tos.icu.Coins.ofNano("999999999000000000"))
        assertNull(assets.getCachedTotalBalance(wallet, currency, true))
        assertTrue(device.wait(Until.hasObject(By.res("network.tos.wallet", "wallet_balance")), 30000))
        fun refresh() {
            val bounds = resource("refresh").visibleBounds
            val start = maxOf(bounds.top + 120, resource("header").visibleBounds.bottom + 120)
            assertTrue(device.swipe(bounds.centerX(), start, bounds.centerX(), minOf(start + 650, bounds.bottom - 100), 50))
        }
        QuantumBalancePoisonServer().use { poison ->
            try {
                api.setCustomTosRpcEndpoint(poison.endpoint)
                // Positive control: the ordinary endpoint really offers a usable
                // false balance. It must not become the Quantum fallback.
                val unproven = runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                    api.getTosBalance(wallet.accountId, wallet.testnet, "TON")
                }
                assertEquals(java.math.BigInteger("999999999000000000"), unproven!!.value.toBigInteger())
                poison.methods.clear()
                refresh()
                val unavailable = device.wait(Until.hasObject(By.text("Balance unavailable")), 30000)
                assertTrue("Missing proof failure UI; methods=${poison.methods}; package=${device.currentPackageName}", unavailable)
                assertFalse(device.hasObject(By.res("network.tos.wallet", "wallet_balance")))
                assertTrue(poison.methods.contains("getProofQuery"))
                assertFalse(poison.methods.contains("getAddressInformation"))
                assertNull(runBlocking { assets.requestTotalBalance(wallet, network.tos.wallet.data.core.currency.WalletCurrency.USD, true) })
                assertNull(runBlocking { assets.getToken(wallet, "TON") })
                assertTrue(runBlocking { assets.getTokens(wallet, listOf("TON")) }.isEmpty())
                click(wallet.label.name)
                assertTrue(device.wait(Until.hasObject(By.text("Add wallet")), 15000))
                assertTrue(device.wait(Until.hasObject(By.text("Balance unavailable")), 15000))
                assertEquals("network.tos.wallet", device.currentPackageName)
                device.pressBack()
            } finally { api.setCustomTosRpcEndpoint(endpoint) }
        }
        // The current selection must not replace the saved wallet's anchor.
        QuantumNetworkProfile(2, "ab".repeat(32), "cd".repeat(32)).save(context)
        refresh()
        assertTrue(device.wait(Until.hasObject(By.res("network.tos.wallet", "wallet_balance")), 30000))
        assertFalse(device.hasObject(By.text("Balance unavailable")))
        assertEquals(java.math.BigInteger.ZERO, runBlocking { repository.readQuantumAccount(wallet) }.balance)
    }

    private fun verifyFundedQuantumTotals(profile: QuantumNetworkProfile, endpoint: String) {
        val args = InstrumentationRegistry.getArguments()
        val identity = network.tos.blockchain.ton.contract.TosQuantumSelfPayIdentity.fromBoc(
            android.util.Base64.decode(requireNotNull(args.getString("quantumFundedIdentity")), android.util.Base64.NO_WRAP))
        val expected = java.math.BigInteger(requireNotNull(args.getString("quantumFundedNanos")))
        assertTrue(expected > java.math.BigInteger.ZERO)
        val id = java.util.UUID.randomUUID()
        try {
            QuantumCreationRecord(id, profile, identity).save(context)
            val transport = network.tos.wallet.data.account.pq.QuantumProofHttpTransport(
                (endpoint + "/jsonRPC").toHttpUrl(), okhttp3.OkHttpClient())
            val request = org.json.JSONObject().put("mode", "live").put("max_age_seconds", 300)
                .put("config_params", org.json.JSONArray(listOf(19))).toString().toByteArray()
            QuantumProofSession(context, id, profile.anchor()).enrollBound(request, System.currentTimeMillis() / 1000, transport)
            val wallet = network.tos.wallet.data.account.entities.WalletEntity(id.toString(),
                network.tos.wallet.data.account.entities.WalletIdentity.Quantum(identity),
                network.tos.wallet.data.account.Wallet.Type.Default,
                network.tos.wallet.data.account.Wallet.Label("Public funded fixture"), initialized = true)
            val assets = GlobalContext.get().get<network.tos.wallet.app.manager.assets.AssetsManager>()
            val total = runBlocking { assets.requestTotalBalance(wallet, network.tos.wallet.data.core.currency.WalletCurrency.USD, true) }
            assertEquals(expected, requireNotNull(total).toBigInteger())
            val item = network.tos.wallet.app.ui.screen.wallet.picker.list.Adapter.map(context, listOf(wallet), wallet,
                network.tos.wallet.data.core.currency.WalletCurrency.USD,
                listOf(network.tos.wallet.app.manager.assets.WalletBalanceEntity(wallet.accountId, false, total)))
                .first() as network.tos.wallet.app.ui.screen.wallet.picker.list.Item.Wallet
            assertTrue(item.balance.toString().contains("TOS"))
            assertFalse(item.balance.toString().contains("$"))
        } finally {
            File(context.noBackupFilesDir, "quantum-proof-checkpoints/$id").deleteRecursively()
            File(context.noBackupFilesDir, "quantum-creation/$id.json").delete()
        }
    }

    /** First half of a process-death test. The runner must retain this installation for the next method. */
    @Test fun mirrorFailureLeavesAnEnrolledWalletForColdResume() {
        val args = InstrumentationRegistry.getArguments()
        api.setCustomTosRpcEndpoint(requireNotNull(args.getString("quantumRpc")))
        QuantumNetworkProfile(requireNotNull(args.getString("quantumGlobalId")).toInt(),
            requireNotNull(args.getString("quantumRootHash")), requireNotNull(args.getString("quantumFileHash"))).save(context)
        val mirror = GlobalContext.get().get<network.tos.wallet.data.rn.RNLegacy>()
        runBlocking { mirror.getWallets() }
        fun database() = android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath("RKStorage").path, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
        database().use { it.execSQL("CREATE TRIGGER interrupt_creation BEFORE INSERT ON catalystLocalStorage WHEN NEW.key='walletsStore' BEGIN SELECT RAISE(ABORT, 'creation interruption'); END") }
        try {
            launch(); click("Create new wallet")
            assertTrue(device.wait(Until.hasObject(By.text("Create passcode")), 15000)); pin()
            assertTrue(device.wait(Until.hasObject(By.text("Re-enter passcode")), 15000)); pin()
            assertTrue(device.wait(Until.hasObject(By.text("Customize your Wallet")), 15000))
            resource("input_field").text = "Interrupted Quantum wallet"
            resource("label_button").click(); pin()
            assertTrue(device.wait(Until.hasObject(By.text("Wallet creation did not finish")), 60000))
            val wallet = runBlocking { repository.getWallets() }.single()
            val record = runBlocking { repository.pendingQuantumCreations() }.single()
            assertEquals(wallet.id, record.id.toString())
            assertTrue(runBlocking { mirror.getWallets().wallets.isEmpty() })
            val prefs = Security.pref(context, "_network_tos_quantum_creation_key_", "quantum_creation_custody", requireUnlockedDevice = true)
            fun digest(role: QuantumRole) = java.security.MessageDigest.getInstance("SHA-256")
                .digest(requireNotNull(prefs.getString("quantum.seed.v1.${role.id}.${wallet.id}", null)).toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            val receipt = org.json.JSONObject().put("id", wallet.id).put("address", record.identity.address.toString()).put("creation_process", android.os.Process.myPid())
                .put("record", org.json.JSONObject(record.encode().toString(Charsets.UTF_8)))
                .put("primary_ciphertext_sha256", digest(QuantumRole.PRIMARY)).put("rescue_ciphertext_sha256", digest(QuantumRole.RESCUE))
            File(context.cacheDir, "quantum-cold-resume-receipt.json").writeText(receipt.toString())
            // A changed creation profile must never redirect this already-enrolled wallet.
            QuantumNetworkProfile(2, "ab".repeat(32), "cd".repeat(32)).save(context)
        } finally { database().use { it.execSQL("DROP TRIGGER interrupt_creation") } }
    }

    /** Second half: start a new application process with the exact persisted state above. */
    @Test fun coldResumeUsesOriginalAnchorAndDoesNotDuplicateWalletOrReplaceKeys() {
        val receipt = org.json.JSONObject(File(context.cacheDir, "quantum-cold-resume-receipt.json").readText())
        val id = receipt.getString("id")
        assertNotEquals(receipt.getInt("creation_process"), android.os.Process.myPid())
        assertEquals(2, QuantumNetworkProfile.selected(context)!!.globalId)
        assertEquals(id, runBlocking { repository.pendingQuantumCreations() }.single().id.toString())
        launch(pending = true)
        click("Create new wallet")
        assertTrue(device.wait(Until.hasObject(By.text("Resume unfinished wallet")), 15000))
        click(receipt.getString("address"))
        assertTrue(device.wait(Until.hasObject(By.text("Customize your Wallet")), 15000))
        resource("label_button").click(); pin()
        assertTrue("Resumed wallet did not reach the main screen", device.wait(Until.hasObject(By.text("TOS")), 60000))
        val wallet = runBlocking { repository.getWallets() }.single()
        assertEquals(id, wallet.id)
        assertEquals(id, runBlocking { repository.getSelectedWallet() }!!.id)
        assertEquals(1, wallet.networkGlobalId)
        val mirror = GlobalContext.get().get<network.tos.wallet.data.rn.RNLegacy>()
        assertEquals(listOf(id), runBlocking { mirror.getWallets().wallets.map { it.identifier } })
        assertTrue(runBlocking { repository.pendingQuantumCreations().isEmpty() })
        val prefs = Security.pref(context, "_network_tos_quantum_creation_key_", "quantum_creation_custody", requireUnlockedDevice = true)
        fun digest(role: QuantumRole) = java.security.MessageDigest.getInstance("SHA-256")
            .digest(requireNotNull(prefs.getString("quantum.seed.v1.${role.id}.$id", null)).toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(receipt.getString("primary_ciphertext_sha256"), digest(QuantumRole.PRIMARY))
        assertEquals(receipt.getString("rescue_ciphertext_sha256"), digest(QuantumRole.RESCUE))
        val record = QuantumCreationRecord.load(context, java.util.UUID.fromString(id))
        assertEquals(receipt.getJSONObject("record").getString("identity"), org.json.JSONObject(record.encode().toString(Charsets.UTF_8)).getString("identity"))
        val again = runBlocking { repository.resumeQuantumCreation(record.id,
            network.tos.wallet.data.account.Wallet.NewLabel(listOf("Retry"), "⭐", 0)) }
        assertEquals(wallet, again)
        assertEquals(listOf(id), runBlocking { mirror.getWallets().wallets.map { it.identifier } })
        assertEquals(receipt.getString("primary_ciphertext_sha256"), digest(QuantumRole.PRIMARY))
        assertEquals(receipt.getString("rescue_ciphertext_sha256"), digest(QuantumRole.RESCUE))
        File(context.cacheDir, "quantum-cold-resume-success.json").writeText(receipt.put("resumed", true).put("resume_process", android.os.Process.myPid()).toString())
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
