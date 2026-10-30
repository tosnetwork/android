package network.tos.wallet

import android.content.ComponentName
import android.content.Intent
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
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
import org.junit.Rule
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/** Run each UI case in a cleared disposable installation. Live creation requires a fresh chain anchor. */
@RunWith(AndroidJUnit4::class)
class QuantumCreationUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private var coldRecovery = false
    private var displayedBackup: List<String>? = null
    @get:Rule val failureEvidence = object : TestWatcher() {
        override fun failed(error: Throwable, description: Description) {
            // Capture before runner cleanup closes the failed product screen.
            runCatching { captureActualPaymentStatus() }
            val secretScreen = coldRecovery || device.hasObject(By.text("Back up your recovery phrase")) ||
                device.hasObject(By.text("Confirm your recovery phrase"))
            if (!secretScreen) runCatching { device.dumpWindowHierarchy(File(context.cacheDir, "quantum-ui-failure.xml")) }
            if (!secretScreen) runCatching { device.takeScreenshot(File(context.cacheDir, "quantum-ui-failure.png")) }
            File(context.cacheDir, "quantum-ui-failure.txt").writeText(description.displayName + "\n" + error.toString())
        }
    }
    private val repository get() = GlobalContext.get().get<AccountRepository>()
    private val api get() = GlobalContext.get().get<API>()
    /** Diagnostics only: read the rendered status TextView even when it is outside the scroll viewport. */
    private fun captureActualPaymentStatus() {
        var text = "[No attached actual payment status TextView]"
        instrumentation.runOnMainSync {
            val activities = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                .filter { it.packageName == context.packageName }
            val matches = arrayListOf<android.view.View>()
            activities.forEach { it.window.decorView.findViewsWithText(matches, "quantum.payment.status",
                android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION) }
            val statuses = matches.filter { it.contentDescription?.toString() == "quantum.payment.status" }
                .filterIsInstance<android.widget.TextView>()
            if (statuses.isNotEmpty()) text = statuses.joinToString("\n") { it.text.toString() }
        }
        File(context.cacheDir, "quantum-ui-failure-status.txt").writeText(text)
    }
    private fun launch(pending: Boolean = false) {
        if (!pending) assertTrue(runBlocking { repository.getWallets().isEmpty() })
        context.startActivity(Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName("network.tos.wallet", "network.tos.wallet.app.ui.screen.root.RootActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        assertTrue(device.wait(Until.hasObject(By.text("Create new wallet")), 15000))
    }
    private fun click(text: String) { checkNotNull(device.wait(Until.findObject(By.text(text)), 15000)) { "Missing $text" }.click() }
    private fun pin() {
        val pad = By.res("network.tos.wallet", "num_pad")
        assertTrue(device.wait(Until.hasObject(pad), 15000))
        "1234".forEachIndexed { index, digit ->
            var center: android.graphics.Point? = null
            repeat(3) { attempt ->
                if (center == null) {
                    device.waitForIdle(3000)
                    val key = checkNotNull(device.wait(Until.findObject(By.text(digit.toString()).enabled(true)
                        .hasAncestor(pad)), 10000)) { "Missing actual passcode key at index $index" }
                    try {
                        val bounds = key.visibleBounds
                        check(!bounds.isEmpty) { "Passcode key is not visible at index $index" }
                        center = android.graphics.Point(bounds.centerX(), bounds.centerY())
                    } catch (_: androidx.test.uiautomator.StaleObjectException) {
                        // Only retry the accessibility snapshot, BEFORE any touch is dispatched.
                        File(context.cacheDir, "quantum-pin-driver.log").appendText("key_index=$index stale_snapshot_attempt=$attempt\n")
                    }
                }
            }
            val point = checkNotNull(center) { "Passcode key did not stabilize at index $index" }
            assertTrue("Actual passcode touch was rejected at index $index", device.click(point.x, point.y))
            File(context.cacheDir, "quantum-pin-driver.log").appendText("key_index=$index one_touch_dispatched\n")
            device.waitForIdle(3000)
        }
    }
    private fun resource(id: String) = checkNotNull(device.wait(Until.findObject(By.res("network.tos.wallet", id)), 15000))

    @androidx.annotation.RequiresApi(26)
    private fun capturePublicHistoryPage() {
        var resumed: android.app.Activity? = null
        instrumentation.runOnMainSync {
            resumed = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                .firstOrNull { it.packageName == context.packageName }
        }
        val activity = checkNotNull(resumed)
        val secure = android.view.WindowManager.LayoutParams.FLAG_SECURE
        val frame = java.util.concurrent.CountDownLatch(1)
        var originalFlags = 0
        instrumentation.runOnMainSync {
            originalFlags = activity.window.attributes.flags
            // Only the disposable public history page is captured. Production
            // privacy defaults and failure/secret screens are never changed.
            activity.window.clearFlags(secure)
            activity.window.decorView.postOnAnimation { frame.countDown() }
        }
        try {
            assertTrue("Product page must normally retain FLAG_SECURE", (originalFlags and secure) != 0)
            assertTrue(frame.await(3, java.util.concurrent.TimeUnit.SECONDS))
            val file = File(context.cacheDir, "quantum-history-product.png")
            var size = intArrayOf(0, 0)
            var captureFlags = 0
            instrumentation.runOnMainSync {
                size = intArrayOf(activity.window.decorView.width, activity.window.decorView.height)
                captureFlags = activity.window.attributes.flags
            }
            assertEquals("Window must allow this public test capture", 0, captureFlags and secure)
            val image = android.graphics.Bitmap.createBitmap(size[0], size[1], android.graphics.Bitmap.Config.ARGB_8888)
            val copied = java.util.concurrent.CountDownLatch(1)
            var copyStatus = android.view.PixelCopy.ERROR_UNKNOWN
            instrumentation.runOnMainSync {
                android.view.PixelCopy.request(activity.window, image, { status ->
                    copyStatus = status; copied.countDown()
                }, android.os.Handler(android.os.Looper.getMainLooper()))
            }
            assertTrue(copied.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("Actual Window frame must be copied", android.view.PixelCopy.SUCCESS, copyStatus)
            file.outputStream().use { assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
            val visible = (200 until image.height - 200 step 16).any { y ->
                (0 until image.width step 16).any { x ->
                    val pixel = image.getPixel(x, y)
                    maxOf(android.graphics.Color.red(pixel), android.graphics.Color.green(pixel), android.graphics.Color.blue(pixel)) > 80
                }
            }
            image.recycle()
            assertTrue("History screenshot must contain actual page content", visible)
        } finally {
            instrumentation.runOnMainSync {
                if ((originalFlags and secure) != 0) activity.window.addFlags(secure) else activity.window.clearFlags(secure)
            }
        }
        var restored = false
        instrumentation.runOnMainSync { restored = (activity.window.attributes.flags and secure) != 0 }
        assertTrue("Product privacy flag must be restored after evidence capture", restored)
    }

    /** Read only the displayed backup, never repository custody or test fixtures. */
    private fun confirmDisplayedQuantumBackup() {
        click("Reveal recovery phrase")
        assertTrue(device.wait(Until.hasObject(By.text("I have written it down")), 30000))
        val words = arrayListOf<String>()
        instrumentation.runOnMainSync {
            val activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).single()
            fun visit(view: android.view.View) {
                if (view is uikit.widget.PhraseWord) words.add(view.text.toString().substringAfter(".  "))
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
            }
            visit(activity.window.decorView)
        }
        assertEquals("Displayed recovery word count", 24, words.size)
        click("I have written it down")
        assertTrue(device.wait(Until.hasObject(By.text("Confirm your recovery phrase")), 15000))
        val inputs = device.findObjects(By.res("network.tos.wallet", "word_input_text"))
        assertEquals(3, inputs.size)
        inputs.forEach { input ->
            val index = input.parent.findObject(By.res("network.tos.wallet", "word_input_index"))
                .text.removeSuffix(":").toInt() - 1
            input.text = words[index]
        }
        if (coldRecovery) displayedBackup = words.toList()
        words.clear()
        device.pressBack()
        click("Confirm and continue")
        assertTrue(device.wait(Until.hasObject(By.text("Customize your Wallet")), 15000))
    }

    @Test fun temporaryProofCleanupRemovesNativeFilesAndRefusesUnexpectedEntries() {
        val id = java.util.UUID.randomUUID()
        val session = QuantumProofSession(context, id, "{}".toByteArray())
        val directory = File(context.noBackupFilesDir, "quantum-proof-checkpoints/$id")
        fun owned(name: String, bytes: ByteArray) = File(directory, name).also {
            it.writeBytes(bytes); android.system.Os.chmod(it.absolutePath, 0x180)
        }
        try {
            owned("checkpoint.json", "{}".toByteArray())
            owned("checkpoint.lock", byteArrayOf())
            owned("initialized", byteArrayOf(1))
            val unexpected = owned("unrelated", byteArrayOf(7))
            assertTrue(runCatching { session.removeCheckpoint() }.isFailure)
            assertTrue(File(directory, "checkpoint.json").exists())
            assertArrayEquals(byteArrayOf(7), unexpected.readBytes())
            assertTrue(unexpected.delete())
            val marker = File(directory, "initialized")
            assertTrue(marker.delete())
            android.system.Os.symlink(File(directory, "checkpoint.json").absolutePath, marker.absolutePath)
            assertTrue(runCatching { session.removeCheckpoint() }.isFailure)
            assertTrue(File(directory, "checkpoint.json").exists())
            android.system.Os.remove(marker.absolutePath)
            owned("initialized", byteArrayOf(1))
            session.removeCheckpoint()
            assertFalse(directory.exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun localNetworkCreatesWithMandatoryBackupAndProtectedKeys() {
        val args = InstrumentationRegistry.getArguments()
        val profile = QuantumNetworkProfile(requireNotNull(args.getString("quantumGlobalId")).toInt(),
            requireNotNull(args.getString("quantumRootHash")), requireNotNull(args.getString("quantumFileHash")))
        api.setCustomTosRpcEndpoint(requireNotNull(args.getString("quantumRpc")))
        profile.save(context)
        launch(); click("Create new wallet")
        val reachedPin = device.wait(Until.hasObject(By.text("Create passcode")), 30000)
        if (!reachedPin) runBlocking { repository.preflightQuantumNetwork() }
        assertTrue("Creation did not reach PIN after successful network preflight", reachedPin); pin()
        assertTrue(device.wait(Until.hasObject(By.text("Re-enter passcode")), 15000)); pin()
        confirmDisplayedQuantumBackup()
        resource("input_field").text = "Quantum local backup"
        resource("label_button").click(); pin()
        val deadline = android.os.SystemClock.elapsedRealtime() + 90000
        var wallets = runBlocking { repository.getWallets() }
        while (wallets.isEmpty() && android.os.SystemClock.elapsedRealtime() < deadline) {
            assertFalse(device.hasObject(By.text("Wallet creation did not finish")))
            android.os.SystemClock.sleep(500); wallets = runBlocking { repository.getWallets() }
        }
        val wallet = wallets.single()
        assertTrue(wallet.isQuantum)
        assertNull(wallet.classicalPublicKey)
        assertNull(runBlocking { repository.getPrivateKey(wallet.id) })
        assertTrue(device.wait(Until.hasObject(By.text("TOS")), 30000))
        assertEquals(wallet.id, runBlocking { repository.getSelectedWallet() }?.id)
        val record = QuantumCreationRecord.decode(File(context.noBackupFilesDir, "quantum-creation/${wallet.id}.json").readBytes())
        assertEquals(profile.globalId, record.profile.globalId)
        assertEquals(profile.rootHash, record.profile.rootHash)
        assertEquals(profile.fileHash, record.profile.fileHash)
        assertEquals(wallet.quantumIdentity, record.identity)
        val authority = record.identity.wallet.initialAuthority
        val binding = QuantumSeedVault.Context(authority.namespace, authority.globalId, 0, 0)
        val prefs = Security.pref(context, "_network_tos_quantum_creation_key_", "quantum_creation_custody", requireUnlockedDevice = true)
        val vault = QuantumSeedVault(prefs)
        val key = QuantumDeviceKey.get(false)
        assertArrayEquals(authority.primaryKey, vault.publicKey(wallet.id, QuantumRole.PRIMARY, binding, key))
        assertArrayEquals(authority.rescueKey, vault.publicKey(wallet.id, QuantumRole.RESCUE, binding, key))
        File(context.cacheDir, "quantum-local-backup-public.json").writeBytes(record.encode())
        if (coldRecovery) {
            coldControl("stage", org.json.JSONObject().put("words", org.json.JSONArray(checkNotNull(displayedBackup)))
                .put("public", publicRecoveryIdentity(record)))
            displayedBackup = null
        }
    }

    @Test fun existingQuantumWalletSurvivesRejectedAndCancelledRecovery() {
        localNetworkCreatesWithMandatoryBackupAndProtectedKeys()
        val original = runBlocking { repository.getWallets() }.single()
        val file = File(context.noBackupFilesDir, "quantum-creation/${original.id}.json")
        val bytes = file.readBytes()
        val record = QuantumCreationRecord.decode(bytes)
        val authority = record.identity.wallet.initialAuthority
        val binding = QuantumSeedVault.Context(authority.namespace, authority.globalId, 0, 0)
        val vault = QuantumSeedVault(Security.pref(context, "_network_tos_quantum_creation_key_", "quantum_creation_custody", requireUnlockedDevice = true))
        val key = QuantumDeviceKey.get(false)
        fun unchanged() {
            val wallets = runBlocking { repository.getWallets() }
            assertEquals(listOf(original.id), wallets.map { it.id })
            assertEquals(original.id, runBlocking { repository.getSelectedWallet() }?.id)
            assertEquals(original.quantumIdentity, wallets.single().quantumIdentity)
            assertArrayEquals(bytes, file.readBytes())
            assertArrayEquals(authority.primaryKey, vault.publicKey(original.id, QuantumRole.PRIMARY, binding, key))
            assertArrayEquals(authority.rescueKey, vault.publicKey(original.id, QuantumRole.RESCUE, binding, key))
        }
        fun openImport() {
            click(original.label.name); click("Add wallet"); click("Import wallet")
            assertTrue(device.wait(Until.hasObject(By.text("Enter recovery phrase")), 15000))
        }
        fun recoveryFormVisible() = device.hasObject(By.res(java.util.regex.Pattern.compile("network.tos.wallet:id/word_[0-9]+")))
        fun returnHome() {
            fun importVisible() = recoveryFormVisible() || device.hasObject(By.text("Enter recovery phrase")) ||
                device.hasObject(By.text("Add wallet")) || device.hasObject(By.text("Import wallet"))
            repeat(6) {
                if (importVisible() || !device.hasObject(By.text("Receive"))) {
                    device.pressBack(); device.waitForIdle(3000)
                }
            }
            assertFalse("Import modal must be closed, not merely reveal the underlying wallet", importVisible())
            assertTrue(device.wait(Until.hasObject(By.text("Receive")), 10000))
            unchanged()
        }
        // Public invalid fixtures only; the real wallet's random backup is never copied here.
        for (phrase in listOf(List(23) { "abandon" }.joinToString(" "),
            (listOf("notaword") + List(23) { "abandon" }).joinToString(" "),
            List(24) { "abandon" }.joinToString(" "))) {
            openImport(); resource("word_1").text = phrase
            val next = device.wait(Until.findObject(By.text("Continue")), 5000)
            if (next != null && next.isEnabled) next.click()
            device.waitForIdle(3000)
            assertTrue("Rejected input must remain in the recovery form", recoveryFormVisible())
            assertFalse(device.hasObject(By.text("Confirm recovery network")))
            assertFalse(device.hasObject(By.text("Customize your Wallet")))
            unchanged(); returnHome()
        }
        openImport()
        resource("word_1").text = "coffee glad rail dry pink piano allow announce system shrug term return vague crater silly state quick glow wrestle wink tail derive device recall"
        click("Continue"); click("Restore Quantum Wallet")
        assertTrue(device.wait(Until.hasObject(By.text("Confirm recovery network")), 15000))
        assertTrue(device.hasObject(By.textContains(record.profile.rootHash)))
        assertTrue(device.hasObject(By.textContains(record.profile.fileHash)))
        val cancel = checkNotNull(device.findObject(By.res("android", "button2")))
        assertTrue(cancel.text.equals("Cancel", ignoreCase = true))
        cancel.click(); unchanged(); returnHome()
        for ((fieldName, wrongValue) in listOf(
            "Global network ID" to (record.profile.globalId + 1).toString(),
            "Zerostate root hash" to "77".repeat(32),
            "Zerostate file hash" to "88".repeat(32))) {
            try {
                openImport()
                resource("word_1").text = "coffee glad rail dry pink piano allow announce system shrug term return vague crater silly state quick glow wrestle wink tail derive device recall"
                click("Continue"); click("Restore Quantum Wallet")
                assertTrue(device.wait(Until.hasObject(By.text("Confirm recovery network")), 15000))
                val change = checkNotNull(device.findObject(By.res("android", "button3")))
                assertTrue(change.text.equals("Change trusted network", ignoreCase = true)); change.click()
                val field = checkNotNull(device.wait(Until.findObject(By.desc(fieldName)), 10000))
                field.text = wrongValue
                val save = checkNotNull(device.findObject(By.res("android", "button1")))
                assertTrue(save.text.equals("Save", ignoreCase = true)); save.click()
                click("Continue"); click("Restore Quantum Wallet")
                assertTrue(device.wait(Until.hasObject(By.text("Confirm recovery network")), 15000))
                assertTrue(device.hasObject(By.textContains(wrongValue)))
                val useNetwork = checkNotNull(device.findObject(By.res("android", "button1")))
                assertTrue(useNetwork.text.equals("Use this network", ignoreCase = true)); useNetwork.click()
                assertTrue("Mismatched $fieldName must fail authenticated preflight",
                    device.wait(Until.hasObject(By.text("Node unavailable")), 30000))
                assertFalse(device.hasObject(By.text("Customize your Wallet")))
                unchanged()
                val dismissFailure = checkNotNull(device.findObject(By.res("android", "button2")))
                assertTrue(dismissFailure.text.equals("Cancel", ignoreCase = true)); dismissFailure.click()
                assertTrue(device.wait(Until.hasObject(By.res(java.util.regex.Pattern.compile(
                    "network.tos.wallet:id/word_[0-9]+"))), 10000))
                returnHome()
            } finally {
                // Restore test configuration; the existing wallet keeps its immutable profile throughout.
                record.profile.save(context)
            }
        }
        click("Receive")
        assertTrue(device.wait(Until.hasObject(By.text("Before funding this Quantum wallet")), 10000))
        click("I understand")
        assertTrue(device.wait(Until.hasObject(By.text(original.address)), 10000))
        unchanged()
    }

    private fun coldControl(action: String, body: org.json.JSONObject = org.json.JSONObject()): org.json.JSONObject {
        val args = InstrumentationRegistry.getArguments()
        val endpoint = requireNotNull(args.getString("quantumColdControl")).toHttpUrl()
        require(endpoint.scheme == "http" && endpoint.host == "10.0.2.2" && endpoint.encodedPath == "/")
        val request = okhttp3.Request.Builder().url(endpoint.newBuilder().addPathSegment(action).build())
            .header("Authorization", "Bearer " + requireNotNull(args.getString("quantumColdToken")))
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return okhttp3.OkHttpClient().newCall(request).execute().use {
            check(it.isSuccessful) { "Cold recovery controller refused stage" }
            org.json.JSONObject(checkNotNull(it.body).string())
        }
    }

    private fun publicRecoveryIdentity(record: QuantumCreationRecord): org.json.JSONObject {
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        val authority = record.identity.wallet.initialAuthority
        return org.json.JSONObject().put("address", "0:" + hex(record.identity.address.address.toByteArray()))
            .put("identity_cell_hash", hex(record.identity.cell.hash().toByteArray()))
            .put("primary", hex(authority.primaryKey)).put("rescue", hex(authority.rescueKey))
            .put("global_id", record.profile.globalId).put("root_hash", record.profile.rootHash)
            .put("file_hash", record.profile.fileHash)
    }

    @Test fun coldRecoveryCreatesAndStagesDisplayedBackup() {
        coldRecovery = true
        try { localNetworkCreatesWithMandatoryBackupAndProtectedKeys() }
        finally { displayedBackup = null }
    }

    @Test fun coldRecoveryRestoresAfterUninstallWithSameAddressAndKeys() {
        coldRecovery = true
        assertTrue(runBlocking { repository.getWallets() }.isEmpty())
        assertFalse(runBlocking { GlobalContext.get().get<PasscodeManager>().hasPinCode() })
        val args = InstrumentationRegistry.getArguments()
        val profile = QuantumNetworkProfile(requireNotNull(args.getString("quantumGlobalId")).toInt(),
            requireNotNull(args.getString("quantumRootHash")), requireNotNull(args.getString("quantumFileHash")))
        profile.save(context)
        api.setCustomTosRpcEndpoint(requireNotNull(args.getString("quantumRpc")))
        val payload = coldControl("restore")
        val words = payload.getJSONArray("words")
        assertEquals(24, words.length())
        launch(); click("Import existing wallet")
        assertTrue(device.wait(Until.hasObject(By.text("Choose from the options below.")), 15000))
        device.findObjects(By.text("Import wallet")).last().click()
        assertTrue(device.wait(Until.hasObject(By.text("Enter recovery phrase")), 15000))
        resource("word_1").text = (0 until words.length()).joinToString(" ") { words.getString(it) }
        payload.remove("words")
        click("Continue")
        assertTrue(device.wait(Until.hasObject(By.text("Choose wallet format")), 15000))
        click("Restore Quantum Wallet")
        assertTrue(device.wait(Until.hasObject(By.text("Confirm recovery network")), 15000))
        assertTrue(device.hasObject(By.textContains(profile.rootHash)))
        assertTrue(device.hasObject(By.textContains(profile.fileHash)))
        val confirmNetwork = checkNotNull(device.findObject(By.res("android", "button1")))
        assertTrue(confirmNetwork.text.equals("Use this network", ignoreCase = true))
        confirmNetwork.click()
        assertTrue(device.wait(Until.hasObject(By.text("Create passcode")), 30000)); pin()
        assertTrue(device.wait(Until.hasObject(By.text("Re-enter passcode")), 15000)); pin()
        assertTrue(device.wait(Until.hasObject(By.text("Customize your Wallet")), 30000))
        resource("input_field").text = "Quantum cold restored"
        resource("label_button").click()
        if (device.wait(Until.hasObject(By.res("network.tos.wallet", "num_pad")), 5000)) pin()
        assertTrue(device.wait(Until.hasObject(By.text("TOS")), 90000))
        val wallet = runBlocking { repository.getWallets() }.single()
        assertTrue(wallet.isQuantum)
        val record = QuantumCreationRecord.decode(File(context.noBackupFilesDir, "quantum-creation/${wallet.id}.json").readBytes())
        assertEquals(wallet.quantumIdentity, record.identity)
        val authority = record.identity.wallet.initialAuthority
        val binding = QuantumSeedVault.Context(authority.namespace, authority.globalId, 0, 0)
        val vault = QuantumSeedVault(Security.pref(context, "_network_tos_quantum_creation_key_", "quantum_creation_custody", requireUnlockedDevice = true))
        val key = QuantumDeviceKey.get(false)
        assertArrayEquals(authority.primaryKey, vault.publicKey(wallet.id, QuantumRole.PRIMARY, binding, key))
        assertArrayEquals(authority.rescueKey, vault.publicKey(wallet.id, QuantumRole.RESCUE, binding, key))
        assertTrue(coldControl("verify", publicRecoveryIdentity(record)).getBoolean("matched"))
    }

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

    @androidx.test.filters.SdkSuppress(minSdkVersion = 26)
    @androidx.annotation.RequiresApi(26)
    @Test fun freshNetworkProductHistoryVerifiesIncomingAndRecoversFromRawCorruption() {
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
        confirmDisplayedQuantumBackup()
        resource("input_field").text = "Quantum history live"
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
        fun control(action: String, body: org.json.JSONObject = org.json.JSONObject()): org.json.JSONObject {
            val request = okhttp3.Request.Builder().url((endpoint + "/__quantum_balance/" + action).toHttpUrl())
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            return okhttp3.OkHttpClient().newCall(request).execute().use { response ->
                check(response.isSuccessful)
                org.json.JSONObject(checkNotNull(response.body).string())
            }
        }
        val rawAddress = "0:" + requireNotNull(wallet.quantumIdentity).address.address.toByteArray()
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(rawAddress, control("fund", org.json.JSONObject().put("address", rawAddress)).getString("address"))
        verifyQuantumBalanceAndFailureRecovery(wallet, endpoint, java.math.BigInteger("10000000000"))
        click("History")
        fun refresh() = checkNotNull(device.wait(Until.findObject(By.res("quantum.history.newest")), 15000)).click()
        val recordSelector = By.res(java.util.regex.Pattern.compile("""quantum\.history\.record\..*"""))
        var row = device.wait(Until.findObject(recordSelector), 10000)
        repeat(4) {
            if (row == null) { refresh(); row = device.wait(Until.findObject(recordSelector), 10000) }
        }
        val recordID = checkNotNull(row) { "Authenticated incoming transaction missing" }.resourceName
        assertTrue(device.hasObject(By.textContains("Incoming transfer")))
        assertTrue(device.hasObject(By.textContains("10 TOS")))
        assertTrue(device.hasObject(By.textContains("Included")))
        row!!.click()
        assertTrue(device.wait(Until.hasObject(By.res("quantum.history.detail")), 10000))
        assertTrue(device.hasObject(By.text("Transaction hash")))
        assertTrue(device.hasObject(By.text("Logical time")))
        assertFalse(device.hasObject(By.textContains("999999999")))
        checkNotNull(device.findObject(By.res("quantum.history.detail.done"))).click()
        control("history-corrupt")
        try {
            refresh()
            assertTrue(device.wait(Until.hasObject(By.desc("Unable to verify history. Refresh to try again.")), 30000))
            assertFalse(device.hasObject(recordSelector))
        } finally { control("history-normal") }
        refresh()
        row = checkNotNull(device.wait(Until.findObject(recordSelector), 30000))
        assertEquals(recordID, row!!.resourceName)
        click("Wallet"); click("History")
        assertTrue(device.wait(Until.hasObject(recordSelector), 30000))
        click("Wallet"); click("TOS")
        assertTrue(device.wait(Until.hasObject(By.res("quantum.history.records")), 15000))
        assertTrue(device.wait(Until.hasObject(recordSelector), 30000))
        assertTrue(device.hasObject(By.text("10 TOS")))
        assertEquals("0", control("status").getString("legacy_history"))
        capturePublicHistoryPage()
        runBlocking { repository.delete(wallet) }
    }

    /** Host must force-stop the App after the live payment case; never clear its data. */
    @Test fun localNetworkRestoresSubmittedPaymentAfterProcessRestart() {
        val args = InstrumentationRegistry.getArguments()
        val expected = requireNotNull(args.getString("quantumExpectedOperation"))
        val digest = requireNotNull(args.getString("quantumExpectedBOC"))
        val wallet = runBlocking { repository.getWallets() }.single()
        val before = checkNotNull(runBlocking { repository.pendingQuantumPayment(wallet) })
        assertEquals(expected, before.id.toString())
        fun hash(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(digest, hash(checkNotNull(before.signedBOC)))
        assertNotNull(before.feeApproval)
        context.startActivity(Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName("network.tos.wallet", "network.tos.wallet.app.ui.screen.root.RootActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        if (device.wait(Until.hasObject(By.res("network.tos.wallet", "num_pad")), 5000)) pin()
        assertTrue(device.wait(Until.hasObject(By.text("TOS")), 30000))
        resource("send").click()
        fun scrollTo(description: String) {
            androidx.test.uiautomator.UiScrollable(androidx.test.uiautomator.UiSelector().scrollable(true))
                .setMaxSearchSwipes(8).scrollIntoView(androidx.test.uiautomator.UiSelector().description(description))
        }
        scrollTo("quantum.payment.verify")
        val verify = checkNotNull(device.wait(Until.findObject(By.desc("quantum.payment.verify").enabled(true)), 30000))
        device.waitForIdle(3000); verify.click()
        androidx.test.uiautomator.UiScrollable(androidx.test.uiautomator.UiSelector().scrollable(true)).scrollToBeginning(8)
        assertTrue("Restart must authenticate the original sender result",
            device.wait(Until.hasObject(By.textContains("The wallet sent the exact transfer.")), 30000))
        val after = checkNotNull(runBlocking { repository.pendingQuantumPayment(wallet) })
        assertEquals(expected, after.id.toString())
        assertEquals(digest, hash(checkNotNull(after.signedBOC)))
        assertEquals(before.phase, after.phase)
        assertEquals(before.feeApproval.toString(), after.feeApproval.toString())
        scrollTo("quantum.payment.submit")
        assertFalse(device.findObject(By.desc("quantum.payment.submit")).isEnabled)
        File(context.cacheDir, "quantum-restart-public-receipt.json").writeText(
            org.json.JSONObject().put("operation", expected).put("message_sha256", digest)
                .put("sender_result", "verified_after_process_restart").toString())
    }

    @Test fun freshNetworkPaysFromActualAppWithProtectedCustody() = actualAppPayment(false)
    @Test fun freshNetworkEndsOriginalAuthorizationAndExplicitlyPaysAgain() = actualAppPayment(true)
    @Test fun freshNetworkEndsExpiredAuthorizationWithUnverifiedOutcome() = actualAppPayment(false, true)
    @Test fun journeyPaysFromRestoredWallet() = actualAppPayment(false, restored = true)
    private fun actualAppPayment(retireAndPayAgain: Boolean, endUnverified: Boolean = false, restored: Boolean = false) {
        val args = InstrumentationRegistry.getArguments()
        val endpoint = requireNotNull(args.getString("quantumRpc"))
        val target = requireNotNull(args.getString("quantumRecipient"))
        val profile = QuantumNetworkProfile(requireNotNull(args.getString("quantumGlobalId")).toInt(),
            requireNotNull(args.getString("quantumRootHash")), requireNotNull(args.getString("quantumFileHash")))
        api.setCustomTosRpcEndpoint(endpoint); profile.save(context)
        if (restored) {
            assertEquals(1, runBlocking { repository.getWallets() }.size)
            context.startActivity(Intent(Intent.ACTION_MAIN).apply {
                component = ComponentName("network.tos.wallet", "network.tos.wallet.app.ui.screen.root.RootActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
            if (device.wait(Until.hasObject(By.res("network.tos.wallet", "num_pad")), 5000)) pin()
        } else {
            launch(); click("Create new wallet")
            assertTrue(device.wait(Until.hasObject(By.text("Create passcode")), 15000)); pin()
            assertTrue(device.wait(Until.hasObject(By.text("Re-enter passcode")), 15000)); pin()
            confirmDisplayedQuantumBackup()
            resource("input_field").text = "Quantum App payment"
            resource("label_button").click(); pin()
        }
        val creationDeadline = android.os.SystemClock.elapsedRealtime() + 90000
        var wallets = runBlocking { repository.getWallets() }
        while (wallets.isEmpty() && android.os.SystemClock.elapsedRealtime() < creationDeadline) {
            assertFalse(device.hasObject(By.text("Wallet creation did not finish")))
            android.os.SystemClock.sleep(500); wallets = runBlocking { repository.getWallets() }
        }
        val wallet = wallets.single()
        if (restored) {
            val record = QuantumCreationRecord.decode(File(context.noBackupFilesDir, "quantum-creation/${wallet.id}.json").readBytes())
            val identity = publicRecoveryIdentity(record)
            assertEquals(requireNotNull(args.getString("quantumExpectedAddress")), identity.getString("address"))
            assertEquals(requireNotNull(args.getString("quantumExpectedIdentity")), identity.getString("identity_cell_hash"))
        }
        assertTrue(wallet.isQuantum)
        assertTrue("Quantum dedicated payment flow must be available", wallet.isQuantumSelfPayAvailable)
        assertFalse("Quantum must not use the classic signing route", wallet.isSendSupported)
        assertNull(runBlocking { repository.getPrivateKey(wallet.id) })
        assertTrue(device.wait(Until.hasObject(By.text("TOS")), 30000))
        val authority = requireNotNull(wallet.quantumIdentity).wallet.initialAuthority
        val binding = QuantumSeedVault.Context(authority.namespace, authority.globalId, 0, 0)
        val prefs = Security.pref(context, "_network_tos_quantum_creation_key_", "quantum_creation_custody", requireUnlockedDevice = true)
        val vault = QuantumSeedVault(prefs)
        assertArrayEquals(authority.primaryKey, vault.publicKey(wallet.id, QuantumRole.PRIMARY, binding, QuantumDeviceKey.get(false)))
        assertTrue(runBlocking { repository.readQuantumAccount(wallet) }.status is network.tos.wallet.data.account.pq.QuantumSelfPayAccount.Status.Absent)
        resource("receive").click()
        assertTrue(device.wait(Until.hasObject(By.text("Before funding this Quantum wallet")), 10000))
        assertTrue(device.hasObject(By.textContains("Public QSI1 account information is not a secret backup")))
        click("I understand"); device.pressBack()
        val rawAddress = "0:" + requireNotNull(wallet.quantumIdentity).address.address.toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) }
        val funding = okhttp3.Request.Builder().url((endpoint + "/__quantum_balance/fund").toHttpUrl())
            .post(org.json.JSONObject().put("address", rawAddress).toString().toRequestBody("application/json".toMediaType())).build()
        okhttp3.OkHttpClient().newCall(funding).execute().use { check(it.isSuccessful) }
        val fundingDeadline = android.os.SystemClock.elapsedRealtime() + 45000
        var balance = runBlocking { repository.readQuantumAccount(wallet) }
        while (balance.balance < java.math.BigInteger("10000000000") && android.os.SystemClock.elapsedRealtime() < fundingDeadline) {
            android.os.SystemClock.sleep(500); balance = runBlocking { repository.readQuantumAccount(wallet) }
        }
        assertEquals(java.math.BigInteger("10000000000"), balance.balance)
        assertTrue(balance.status is network.tos.wallet.data.account.pq.QuantumSelfPayAccount.Status.Uninitialized)
        if (restored) {
            verifyQuantumBalanceAndFailureRecovery(wallet, endpoint, java.math.BigInteger("10000000000"))
            click("History")
            val rowSelector = By.res(java.util.regex.Pattern.compile("""quantum\.history\.record\..*"""))
            var row = device.wait(Until.findObject(rowSelector), 10000)
            repeat(4) {
                if (row == null) {
                    checkNotNull(device.wait(Until.findObject(By.res("quantum.history.newest")), 10000)).click()
                    row = device.wait(Until.findObject(rowSelector), 10000)
                }
            }
            assertNotNull("Recovered wallet must authenticate its actual incoming funding", row)
            assertTrue(device.hasObject(By.textContains("Incoming transfer")))
            assertTrue(device.hasObject(By.textContains("10 TOS")))
            assertTrue(device.hasObject(By.textContains("Included")))
            click("Wallet")
        }
        resource("send").click()
        fun field(description: String) = checkNotNull(device.wait(Until.findObject(By.desc(description)), 15000))
        var pressSequence = 0
        fun press(description: String) {
            val sequence = ++pressSequence
            check(sequence <= 64) { "Bounded payment driver action budget exhausted" }
            val trace = File(context.cacheDir, "quantum-press-driver.jsonl")
            fun log(stage: String, value: org.json.JSONObject = org.json.JSONObject()) {
                value.put("sequence", sequence).put("stage", stage).put("description", description)
                    .put("elapsed_ms", android.os.SystemClock.elapsedRealtime())
                check(trace.length() <= 131072) { "Bounded payment driver log exhausted" }
                trace.appendText(value.toString() + "\n")
            }
            fun hierarchy(stage: String) {
                val file = File(context.cacheDir, "quantum-press-$stage.xml")
                runCatching {
                    device.dumpWindowHierarchy(file)
                    check(file.length() <= 2097152) { "Bounded driver hierarchy exceeded" }
                }.onFailure { log("$stage-hierarchy-error", org.json.JSONObject().put("error", it.javaClass.name)) }
            }
            device.waitForIdle(3000)
            val visible = device.findObject(By.desc(description))
            if (visible == null || visible.visibleBounds.isEmpty) {
                check(device.hasObject(By.scrollable(true))) { "Missing visible product button: $description" }
                androidx.test.uiautomator.UiScrollable(androidx.test.uiautomator.UiSelector().scrollable(true))
                    .setMaxSearchSwipes(8).scrollIntoView(androidx.test.uiautomator.UiSelector().description(description))
            }
            // The first tap can otherwise stop a still-moving ScrollView instead of reaching onClick.
            device.waitForIdle(3000)
            val target = checkNotNull(device.wait(Until.findObject(By.desc(description).enabled(true)), 15000))
            check(!target.visibleBounds.isEmpty) { "Product button is not visible after idle: $description" }
            val bounds = target.visibleBounds
            val center = target.visibleCenter
            log("before-click", org.json.JSONObject().put("enabled", target.isEnabled).put("clickable", target.isClickable)
                .put("visible_bounds", bounds.flattenToString()).put("center_x", center.x).put("center_y", center.y))
            hierarchy("pre")
            try {
                if (description == "quantum.payment.verify") {
                    val event = instrumentation.uiAutomation.executeAndWaitForEvent(
                        { target.click() },
                        { candidate ->
                            val source = candidate.source
                            try {
                                candidate.eventType == android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED &&
                                    source?.contentDescription?.toString() == description &&
                                    source?.packageName?.toString() == context.packageName
                            } finally { source?.recycle() }
                        }, 5000)
                    try { log("exact-view-clicked", org.json.JSONObject().put("event_type", event.eventType)) }
                    finally { event.recycle() }
                } else target.click()
                log("single-click-returned")
            } catch (error: Exception) {
                log("single-click-error", org.json.JSONObject().put("error", error.javaClass.name))
                throw error
            } finally { hierarchy("post") }
            device.waitForIdle(3000)
        }
        var endPress = 0
        fun confirmEndAuthorization() {
            val title = device.wait(Until.findObject(By.res("network.tos.wallet", "alertTitle")
                .text("Verify expiry and end this authorization?")), 15000)
            device.dumpWindowHierarchy(File(context.cacheDir, "quantum-end-post-press-${endPress++}.xml"))
            assertNotNull("Actual product end confirmation did not appear", title)
            val positive = checkNotNull(device.wait(Until.findObject(By.res("android", "button1").enabled(true)), 10000))
            assertEquals("VERIFY AND END", positive.text)
            assertFalse(positive.visibleBounds.isEmpty)
            positive.click()
            assertTrue("End confirmation did not dismiss", device.wait(Until.gone(By.res("android", "button1")), 10000))
            assertNotNull("Actual payment view did not return after confirmation",
                device.wait(Until.findObject(By.desc("quantum.payment.end")), 10000))
            device.waitForIdle(3000)
        }
        fun top() {
            if (device.hasObject(By.scrollable(true))) androidx.test.uiautomator.UiScrollable(
                androidx.test.uiautomator.UiSelector().scrollable(true)).scrollToBeginning(8)
        }
        field("quantum.payment.recipient").text = target
        field("quantum.payment.amount").text = "1"
        field("quantum.payment.comment").text = "Android App protected custody"
        press("quantum.payment.review")
        top()
        assertTrue(device.wait(Until.hasObject(By.textContains("The first payment also deploys this wallet")), 30000))
        assertNull(runBlocking { repository.pendingQuantumPayment(wallet) })
        press("quantum.payment.sign")
        pin()
        assertTrue(device.wait(Until.gone(By.res("network.tos.wallet", "num_pad")), 10000))
        top()
        assertTrue(device.wait(Until.hasObject(By.textContains("Estimated total debit:")), 45000))
        val signed = checkNotNull(runBlocking { repository.pendingQuantumPayment(wallet) })
        assertEquals(network.tos.wallet.data.account.pq.QuantumPaymentOutbox.Phase.SIGNED, signed.phase)
        assertTrue(signed.deploy); assertNull(signed.feeApproval)
        val originalBOC = checkNotNull(signed.signedBOC)
        press("quantum.payment.submit")
        top()
        assertTrue(device.wait(Until.hasObject(By.textContains("Payment outcome remains unverified")), 30000))
        val submitted = checkNotNull(runBlocking { repository.pendingQuantumPayment(wallet) })
        assertEquals(signed.id, submitted.id)
        assertArrayEquals(originalBOC, submitted.signedBOC)
        assertEquals(network.tos.wallet.data.account.pq.QuantumPaymentOutbox.Phase.UNKNOWN, submitted.phase)
        assertNotNull(submitted.feeApproval)
        androidx.test.uiautomator.UiScrollable(androidx.test.uiautomator.UiSelector().scrollable(true))
            .scrollIntoView(androidx.test.uiautomator.UiSelector().description("quantum.payment.recoverQuote"))
        assertFalse("Unknown must not restore a broadcast capability", field("quantum.payment.recoverQuote").isEnabled)
        var sent = false
        if (!endUnverified) {
            repeat(6) {
                if (!sent) {
                    press("quantum.payment.verify")
                    top()
                    sent = device.wait(Until.hasObject(By.textContains("The wallet sent the exact transfer.")), 15000)
                }
            }
            assertTrue("Actual App did not prove the exact sender transfer", sent)
            assertTrue(device.hasObject(By.textContains("Recipient credit is a separate observation.")))
        } else {
            assertFalse("Unverified-expiry acceptance must not request or claim a receipt", sent)
        }
        val retainedAfterReceipt = checkNotNull(runBlocking { repository.pendingQuantumPayment(wallet) })
        assertEquals(signed.id, retainedAfterReceipt.id); assertArrayEquals(originalBOC, retainedAfterReceipt.signedBOC)
        assertEquals(network.tos.wallet.data.account.pq.QuantumPaymentOutbox.Phase.UNKNOWN, retainedAfterReceipt.phase)
        assertTrue(runBlocking { repository.readQuantumAccount(wallet) }.status is network.tos.wallet.data.account.pq.QuantumSelfPayAccount.Status.Active)
        if (!endUnverified) {
            File(context.cacheDir, "quantum-app-payment-public-receipt.json").writeText(org.json.JSONObject()
                .put("scope", "actual App protected-custody sender transfer; current dedicated Quantum flow")
                .put("wallet", rawAddress).put("local_wallet_id", wallet.id)
                .put("birth_identity_hash", requireNotNull(wallet.quantumIdentity).cell.hash().toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) })
                .put("recipient", target).put("operation", signed.id.toString())
                .put("message_sha256", java.security.MessageDigest.getInstance("SHA-256").digest(originalBOC).joinToString("") { "%02x".format(it.toInt() and 255) })
                .put("signature_deadline", signed.deadline).put("phase", submitted.phase.name).put("fee_approval", submitted.feeApproval).toString(2))
        }
        val birthHash = requireNotNull(wallet.quantumIdentity).cell.hash().toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) }
        val journal = File(context.noBackupFilesDir, "quantum-payment-outbox/$birthHash.json")
        File(context.cacheDir, "quantum-app-first-public-outbox.json").writeBytes(journal.readBytes())
        if (!retireAndPayAgain && !endUnverified) return // The original single-payment acceptance retains its UNKNOWN slot.

        assertTrue(field("quantum.payment.details").text.contains("Original authorization deadline:"))
        // Wait for quote expiry solely to schedule a negative UI attempt. The product still obtains
        // a fresh native proof; the local clock cannot authorize retirement.
        val quoteDeadline = checkNotNull(submitted.feeApproval).getLong("expires_at")
        val quoteWait = quoteDeadline + 1 - System.currentTimeMillis() / 1000
        check(quoteWait <= 60)
        if (quoteWait > 0) android.os.SystemClock.sleep(quoteWait * 1000)
        check(System.currentTimeMillis() / 1000 < signed.deadline - 10) { "Before-original-deadline UI precondition elapsed" }
        press("quantum.payment.end"); confirmEndAuthorization(); top()
        assertTrue(device.wait(Until.hasObject(By.textContains("The original authorization has not been proven expired.")), 30000))
        val stillPending = checkNotNull(runBlocking { repository.pendingQuantumPayment(wallet) })
        assertEquals(signed.id, stillPending.id); assertArrayEquals(originalBOC, stillPending.signedBOC)
        assertTrue(runBlocking { repository.quantumLocalPaymentHistory(wallet) }.isEmpty())
        press("quantum.payment.reload"); top()
        val expiryWait = signed.deadline + 2 - System.currentTimeMillis() / 1000
        check(expiryWait <= 150)
        if (expiryWait > 0) android.os.SystemClock.sleep(expiryWait * 1000)
        var ended = false
        val endingDeadline = android.os.SystemClock.elapsedRealtime() + 90000
        while (!ended && android.os.SystemClock.elapsedRealtime() < endingDeadline) {
            if (!endUnverified) {
                // Refresh the actual sender receipt too; no saved JSON becomes a receipt capability.
                press("quantum.payment.verify"); top()
                assertTrue(device.wait(Until.hasObject(By.textContains("The wallet sent the exact transfer.")), 30000))
            }
            press("quantum.payment.end"); confirmEndAuthorization(); top()
            ended = device.wait(Until.hasObject(By.textContains(if (endUnverified)
                "Expired; payment outcome unverified." else "The old authorization has ended.")), 15000)
            if (!ended) {
                assertTrue(device.hasObject(By.textContains("The original authorization has not been proven expired.")))
                press("quantum.payment.reload"); top(); android.os.SystemClock.sleep(2000)
            }
        }
        assertTrue("Actual App did not durably end the expired original authorization", ended)
        assertNull(runBlocking { repository.pendingQuantumPayment(wallet) })
        val archived = runBlocking { repository.quantumLocalPaymentHistory(wallet) }.single()
        assertEquals(signed.id, archived.operationId)
        assertEquals(network.tos.wallet.data.account.pq.QuantumAuthorizationRetirement.State.RELEASE_COMPLETED, archived.state)
        assertEquals(if (endUnverified) network.tos.wallet.data.account.pq.QuantumAuthorizationRetirement.Result.EXPIRED_UNVERIFIED
            else network.tos.wallet.data.account.pq.QuantumAuthorizationRetirement.Result.SENT, archived.result)
        File(context.cacheDir, "quantum-app-first-local-summary.json").writeBytes(
            File(context.noBackupFilesDir, "quantum-payment-outbox/.retirement-history/${signed.id}.json").readBytes())
        if (endUnverified) {
            assertNull("Unverified expiry must not fabricate a sender transaction", archived.transactionHash)
            assertNull(archived.logicalTime)
            assertFalse("Ending an unverified authorization must not open a new payment", field("quantum.payment.recipient").isEnabled)
            assertNull(runBlocking { repository.pendingQuantumPayment(wallet) })
            return
        }
        assertFalse("Ended payment silently resumed editing", field("quantum.payment.recipient").isEnabled)
        press("quantum.payment.reload"); top()
        assertTrue(device.wait(Until.hasObject(By.textContains("The old authorization ended.")), 15000))
        assertFalse("Reload bypassed explicit new-payment consent", field("quantum.payment.recipient").isEnabled)
        onView(withContentDescription("quantum.payment.recipient")).check(matches(withText("")))
        onView(withContentDescription("quantum.payment.amount")).check(matches(withText("")))
        onView(withContentDescription("quantum.payment.comment")).check(matches(withText("")))
        // Close and reopen via the actual main Send route: this is a fresh Fragment with no
        // transient `ended` flag. Its durable local history must restore the UI consent gate.
        press("quantum.payment.close")
        resource("send").click(); top()
        assertTrue(device.wait(Until.hasObject(By.textContains("The old authorization ended.")), 15000))
        assertFalse("Recreated page bypassed explicit new-payment consent", field("quantum.payment.recipient").isEnabled)
        onView(withContentDescription("quantum.payment.recipient")).check(matches(withText("")))
        onView(withContentDescription("quantum.payment.amount")).check(matches(withText("")))
        onView(withContentDescription("quantum.payment.comment")).check(matches(withText("")))
        press("quantum.payment.localHistory")
        assertTrue(device.wait(Until.hasObject(By.textContains("Retirement completed locally.")), 15000))
        assertTrue(field("quantum.payment.localHistory.details").text.contains(signed.id.toString()))
        assertNull(runBlocking { repository.pendingQuantumPayment(wallet) })
        press("quantum.payment.new"); top()
        onView(withContentDescription("quantum.payment.recipient")).check(matches(withText("")))
        onView(withContentDescription("quantum.payment.amount")).check(matches(withText("")))
        onView(withContentDescription("quantum.payment.comment")).check(matches(withText("")))
        field("quantum.payment.recipient").text = target
        field("quantum.payment.amount").text = "0.5"
        field("quantum.payment.comment").text = "Android App explicit second payment"
        press("quantum.payment.review"); top()
        assertTrue(device.wait(Until.hasObject(By.textContains("Review the exact recipient")), 30000))
        assertFalse(field("quantum.payment.details").text.contains("first payment also deploys"))
        assertNull(runBlocking { repository.pendingQuantumPayment(wallet) })
        press("quantum.payment.sign"); pin()
        assertTrue(device.wait(Until.gone(By.res("network.tos.wallet", "num_pad")), 10000)); top()
        assertTrue(device.wait(Until.hasObject(By.textContains("Estimated total debit:")), 45000))
        assertTrue(field("quantum.payment.details").text.contains("Fee quote expires:"))
        assertTrue(field("quantum.payment.details").text.contains("Original authorization deadline:"))
        val secondSigned = checkNotNull(runBlocking { repository.pendingQuantumPayment(wallet) })
        assertNotEquals(signed.id, secondSigned.id); assertFalse(secondSigned.deploy)
        assertEquals(java.math.BigInteger("500000000"), secondSigned.intent.amount)
        assertFalse(originalBOC.contentEquals(checkNotNull(secondSigned.signedBOC)))
        val firstRequest = network.tos.blockchain.ton.contract.QuantumStoredSelfPayRequest.parse(signed.requestBOC)
        val secondRequest = network.tos.blockchain.ton.contract.QuantumStoredSelfPayRequest.parse(secondSigned.requestBOC)
        assertEquals(firstRequest.nonce + 1uL, secondRequest.nonce)
        assertTrue(secondSigned.deadline > signed.deadline)
        val secondBOC = checkNotNull(secondSigned.signedBOC)
        press("quantum.payment.submit"); top()
        assertTrue(device.wait(Until.hasObject(By.textContains("Payment outcome remains unverified")), 30000))
        var secondSent = false
        repeat(6) {
            if (!secondSent) { press("quantum.payment.verify"); top(); secondSent = device.wait(Until.hasObject(By.textContains("The wallet sent the exact transfer.")), 15000) }
        }
        assertTrue("Explicit second App payment has no authenticated sender receipt", secondSent)
        val second = checkNotNull(runBlocking { repository.pendingQuantumPayment(wallet) })
        assertEquals(secondSigned.id, second.id); assertArrayEquals(secondBOC, second.signedBOC)
        assertEquals(network.tos.wallet.data.account.pq.QuantumPaymentOutbox.Phase.UNKNOWN, second.phase)
        assertEquals(1, runBlocking { repository.quantumLocalPaymentHistory(wallet) }.size)
        File(context.cacheDir, "quantum-app-second-payment-public-receipt.json").writeText(org.json.JSONObject()
            .put("scope", "actual App explicit second payment after proven original expiry and durable retirement; availability-only candidate")
            .put("wallet", rawAddress).put("local_wallet_id", wallet.id).put("birth_identity_hash", birthHash)
            .put("recipient", target).put("operation", second.id.toString()).put("amount_nano", "500000000")
            .put("message_sha256", java.security.MessageDigest.getInstance("SHA-256").digest(secondBOC).joinToString("") { "%02x".format(it.toInt() and 255) })
            .put("signature_deadline", second.deadline).put("phase", second.phase.name).put("fee_approval", second.feeApproval).toString(2))
        File(context.cacheDir, "quantum-app-second-public-outbox.json").writeBytes(journal.readBytes())
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

    private fun verifyQuantumBalanceAndFailureRecovery(wallet: network.tos.wallet.data.account.entities.WalletEntity, endpoint: String,
        expected: java.math.BigInteger = java.math.BigInteger.ZERO) {
        val assets = GlobalContext.get().get<network.tos.wallet.app.manager.assets.AssetsManager>()
        val currency = network.tos.wallet.data.core.currency.WalletCurrency.TON
        val balance = runBlocking { repository.readQuantumAccount(wallet) }
        assertTrue(balance.status is network.tos.wallet.data.account.pq.QuantumSelfPayAccount.Status.Absent ||
            balance.status is network.tos.wallet.data.account.pq.QuantumSelfPayAccount.Status.Uninitialized)
        assertEquals(expected, balance.balance)
        assertEquals(expected, runBlocking { assets.getTokens(wallet, currency, true) }.single().balance.toBigInteger())
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
        assertEquals(expected, runBlocking { repository.readQuantumAccount(wallet) }.balance)
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
            network.tos.wallet.data.account.Wallet.NewLabel(listOf("Retry"), "⭐", 0),
            backupConfirmed = true) }
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
