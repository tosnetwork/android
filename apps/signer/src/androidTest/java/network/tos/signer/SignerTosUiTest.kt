package network.tos.signer

import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import network.tos.blockchain.TosV1Mnemonic
import network.tos.blockchain.ton.contract.TosWalletV5R1Contract
import network.tos.blockchain.ton.extensions.hex
import network.tos.blockchain.ton.extensions.cellFromBase64
import network.tos.blockchain.ton.extensions.asCellRef
import network.tos.signer.core.repository.KeyRepository
import network.tos.signer.deeplink.DeeplinkSource
import network.tos.signer.deeplink.entities.ReturnResultEntity
import network.tos.signer.deeplink.entities.SignRequestEntity
import network.tos.signer.password.Password
import network.tos.signer.vault.SignerVault
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.ton.block.AddrStd
import org.ton.block.Coins
import org.ton.cell.Cell
import org.ton.contract.wallet.WalletTransferBuilder

@RunWith(AndroidJUnit4::class)
class SignerTosUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val publicKey = TosV1Mnemonic.privateKey(NATIVE_PHRASE.split(" ")).publicKey()

    private fun body(mode: Int = 3, comment: String? = null): Cell {
        val gift = WalletTransferBuilder().apply {
            destination = AddrStd.parse("0:${"11".repeat(32)}")
            coins = Coins.ofNano(123456789)
            sendMode = mode
            bounceable = true
            if (comment != null) messageData = org.ton.contract.wallet.MessageData.Raw(requireNotNull(asCellRef(comment)), null)
        }.build()
        return TosWalletV5R1Contract(publicKey, 3).createTransferUnsignedBody(
            System.currentTimeMillis() / 1000 + 600, 7, false, null, gift)
    }
    private fun uri(body: Cell, network: Int = 3, version: String? = "tosv5r1",
        key: org.ton.api.pub.PublicKeyEd25519 = publicKey): Uri = Uri.Builder().scheme(Key.SCHEME).authority("v1")
        .appendQueryParameter(Key.PK, key.hex()).appendQueryParameter(Key.BODY, body.hex())
        .apply { version?.let { appendQueryParameter(Key.V, it) } }.appendQueryParameter(Key.SEQNO, "7")
        .appendQueryParameter(Key.NETWORK, network.toString()).build()

    private fun ensureNativeKey() = runBlocking {
        val repository = GlobalContext.get().get<KeyRepository>()
        repository.stream.first()
        val id = repository.findIdByPublicKey(publicKey) ?: repository.addKey("PUBLIC TOS UI TEST", publicKey).id
        val vault = GlobalContext.get().get<SignerVault>()
        val secret = if (vault.hasPassword()) vault.getMasterSecret("1234".toCharArray())
            else vault.createMasterSecret("1234".toCharArray())
        vault.setMnemonic(secret, id, NATIVE_PHRASE.split(" "), nativeTos = true)
        assertTrue(vault.isNativeTosKey(id))
        Password.setUnlock()
    }

    @Test fun nativeConfirmationShowsExactTosAmountAndNetworkAndOnlyLocalRawExport() {
        ensureNativeKey()
        // A unique visible comment proves this request replaced any prior confirmation.
        val comment = "TOS 安全 🌌 ${System.currentTimeMillis()}"
        val requestBody = body(comment = comment)
        context.startActivity(Intent(Intent.ACTION_VIEW, uri(requestBody)).apply {
            component = ComponentName(context.packageName, "network.tos.signer.screen.root.RootActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        assertTrue(device.wait(Until.hasObject(By.text("Sign transaction")), 20_000))
        assertTrue(device.wait(Until.hasObject(By.text("TOS · Network 3")), 10_000))
        assertTrue(device.wait(Until.hasObject(By.text("0.123456789 TOS")), 10_000))
        assertTrue(device.wait(Until.hasObject(By.text(comment)), 10_000))
        assertFalse(device.hasObject(By.textContains("TON")))
        device.waitForIdle()
        device.findObject(By.res(context.packageName, "show_audit")).click()
        device.waitForIdle()
        assertFalse(device.hasObject(By.res(context.packageName, "emulate")))
        assertFalse(device.hasObject(By.res(context.packageName, "qr")))
        device.findObject(By.res(context.packageName, "copy")).click()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val copyDeadline = android.os.SystemClock.uptimeMillis() + 10_000
        while (clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() != requestBody.hex()
            && android.os.SystemClock.uptimeMillis() < copyDeadline) {
            Thread.sleep(50)
        }
        assertEquals("Copy must export this visibly confirmed request, not a previous clipboard entry",
            requestBody.hex(), clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString())
        assertEquals(context.packageName, device.currentPackageName)

        // An ordinary legacy key/request still reaches its original confirmation.
        val legacyKey = TosV1Mnemonic.recoveryPrivateKey(LEGACY_PHRASE.split(" "), TosV1Mnemonic.Profile.LEGACY).publicKey()
        runBlocking {
            val repository = GlobalContext.get().get<KeyRepository>()
            val id = repository.findIdByPublicKey(legacyKey) ?: repository.addKey("PUBLIC LEGACY UI TEST", legacyKey).id
            val vault = GlobalContext.get().get<SignerVault>()
            vault.setMnemonic(vault.getMasterSecret("1234".toCharArray()), id, LEGACY_PHRASE.split(" "))
            assertFalse(vault.isNativeTosKey(id))
        }
        val gift = WalletTransferBuilder().apply {
            destination = AddrStd.parse("0:${"11".repeat(32)}"); coins = Coins.ofNano(1_000_000_000L); sendMode = 3
        }.build()
        val legacyBody = network.tos.blockchain.ton.contract.BaseWalletContract.create(legacyKey, "v4r2", -239)
            .createTransferUnsignedBody(System.currentTimeMillis() / 1000 + 600, 7, false, null, gift)
        context.startActivity(Intent(Intent.ACTION_VIEW, uri(legacyBody, -239, "v4r2", legacyKey)).apply {
            component = ComponentName(context.packageName, "network.tos.signer.screen.root.RootActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        assertTrue(device.wait(Until.hasObject(By.text("Sign transaction")), 20_000))
        assertTrue(device.wait(Until.hasObject(By.textContains("TON")), 10_000))
        assertFalse(device.hasObject(By.text("TOS · Network 3")))
        device.findObject(By.res(context.packageName, "show_audit")).click()
        assertTrue(device.hasObject(By.res(context.packageName, "emulate")))
        assertTrue(device.hasObject(By.res(context.packageName, "qr")))
    }

    @Test fun authenticatedNativeRequestWithoutCallbackPublishesValidLocalSignatureQr() {
        ensureNativeKey()
        val requestBody = body()
        val request = uri(requestBody)
        assertNull(request.getQueryParameter("return"))
        context.startActivity(Intent(Intent.ACTION_VIEW, request).apply {
            component = ComponentName(context.packageName, "network.tos.signer.screen.root.RootActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        assertTrue(device.wait(Until.hasObject(By.text("0.123456789 TOS")), 20_000))
        val slide = requireNotNull(device.wait(Until.findObject(By.res(context.packageName, "slide")), 10_000))
        // Wait for the confirmation sheet to finish entering before measuring its handle.
        device.waitForIdle()
        val bounds = slide.visibleBounds
        val handle = requireNotNull(slide.findObject(By.res(context.packageName, "button"))).visibleBounds
        assertTrue(device.swipe(handle.centerX(), handle.centerY(), bounds.right - 1, handle.centerY(), 160))
        val password = device.wait(Until.findObject(By.res(context.packageName, "internal_input")), 10_000)
        if (password == null) {
            device.dumpWindowHierarchy(java.io.File(context.cacheDir, "native-auth-missing-password.xml"))
            device.takeScreenshot(java.io.File(context.cacheDir, "native-auth-missing-password.png"))
        }
        requireNotNull(password) { "Dragging the visible sign handle must open the authentication dialog" }
        password.text = "1234"
        device.findObject(By.res(context.packageName, "password_button")).click()
        assertTrue("Signing without a callback must show a local signature QR",
            device.wait(Until.hasObject(By.res(context.packageName, "done")), 20_000))
        val qr = requireNotNull(device.findObject(By.res(context.packageName, "qr")))
        val qrBounds = qr.visibleBounds
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val qrBitmap = android.graphics.Bitmap.createBitmap(screenshot,
            qrBounds.left, qrBounds.top, qrBounds.width(), qrBounds.height())
        val scanner = com.google.mlkit.vision.barcode.BarcodeScanning.getClient(
            com.google.mlkit.vision.barcode.BarcodeScannerOptions.Builder()
                .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE).build())
        try {
            val decoded = com.google.android.gms.tasks.Tasks.await(
                scanner.process(com.google.mlkit.vision.common.InputImage.fromBitmap(qrBitmap, 0)),
                30, java.util.concurrent.TimeUnit.SECONDS)
            val published = Uri.parse(requireNotNull(decoded.single().rawValue))
            assertEquals("tos", published.scheme)
            assertEquals("publish", published.host)
            val expected = network.tos.security.hex(
                TosV1Mnemonic.privateKey(NATIVE_PHRASE.split(" ")).sign(requestBody.hash().toByteArray()))
            assertEquals("QR must contain the signature of the confirmed unsigned body",
                expected, published.getQueryParameter(Key.SIGN))
            assertEquals(context.packageName, device.currentPackageName)
        } finally {
            scanner.close()
            qrBitmap.recycle()
            screenshot.recycle()
        }
    }

    @Test fun hiddenSweepAndMismatchedNetworkCannotOpenNativeConfirmation() {
        val returns = ReturnResultEntity(DeeplinkSource.App, null as Uri?)
        ensureNativeKey()
        val dangerous = instrumentation.context.assets.open("tos-signer-boundary-vectors.tsv").bufferedReader().use { it.readLines() }
            .filter { it.isNotBlank() && !it.startsWith("#") }.flatMap { line ->
                listOf("tosv5r1", "v4r2", null, "unknown").map { label -> uri(line.split('\t')[1].cellFromBase64(), version = label) }
            }
        val requests = dangerous + listOf(uri(body(130)), uri(body(), 4))
        assertEquals(18, requests.size)
        for (request in requests) {
            assertNull(SignRequestEntity.safe(request, returns))
            context.startActivity(Intent(Intent.ACTION_VIEW, request).apply {
                component = ComponentName(context.packageName, "network.tos.signer.screen.root.RootActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
            device.wait(Until.hasObject(By.pkg(context.packageName)), 10_000)
            android.os.SystemClock.sleep(750)
            assertFalse("Dangerous deep link opened confirmation", device.hasObject(By.text("Sign transaction")))
            assertFalse("Dangerous deep link exposed sign control", device.hasObject(By.res(context.packageName, "slide")))
        }
        // Even real legacy bytes cannot use a native key's less strict profile.
        val gift = WalletTransferBuilder().apply {
            destination = AddrStd.parse("0:${"11".repeat(32)}"); coins = Coins.ofNano(1L); sendMode = 3
        }.build()
        val legacyBody = network.tos.blockchain.ton.contract.BaseWalletContract.create(publicKey, "v4r2", -239)
            .createTransferUnsignedBody(System.currentTimeMillis() / 1000 + 600, 7, false, null, gift)
        val relabeledNativeKey = uri(legacyBody, -239, "v4r2")
        assertNotNull(SignRequestEntity.safe(relabeledNativeKey, returns))
        context.startActivity(Intent(Intent.ACTION_VIEW, relabeledNativeKey).apply {
            component = ComponentName(context.packageName, "network.tos.signer.screen.root.RootActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        device.wait(Until.hasObject(By.pkg(context.packageName)), 10_000)
        android.os.SystemClock.sleep(750)
        assertFalse(device.hasObject(By.text("Sign transaction")))
        assertFalse(device.hasObject(By.res(context.packageName, "slide")))
    }

    @Test fun persistedNativeAndLegacyVaultFormatsKeepTheirOwnDualValidKeys() = runBlocking {
        val vault = SignerVault(context, "PUBLIC_TEST_DERIVATION")
        try {
            vault.setMnemonic(vault.createMasterSecret("1234".toCharArray()), 1,
                DUAL_PHRASE.split(" "), nativeTos = false)
            assertFalse(vault.isNativeTosKey(1))
            assertEquals("cfe05748559fea1f676ba2e6ef9d2e2bf5767a59fe66584c0fc5fea1cb772166",
                vault.getPrivateKey(vault.getMasterSecret("1234".toCharArray()), 1).publicKey().hex())
            vault.setMnemonic(vault.getMasterSecret("1234".toCharArray()), 2,
                DUAL_PHRASE.split(" "), nativeTos = true)
            assertTrue(vault.isNativeTosKey(2))
            assertEquals("7c2c64e1dca71c1add3777ebaeb611ad56229995d435ad7bf6ba29909d816ceb",
                vault.getPrivateKey(vault.getMasterSecret("1234".toCharArray()), 2).publicKey().hex())
        } finally { vault.deleteAll() }
    }

    companion object {
        // PUBLIC TEST DATA. Never use these phrases for real assets.
        private const val NATIVE_PHRASE = "enhance depend evolve rotate creek total enable settle mammal margin round cube truck quote hold correct provide voyage north model sure off strategy pulse"
        private const val DUAL_PHRASE = "coffee glad rail dry pink piano allow announce system shrug term return vague crater silly state quick glow wrestle wink tail derive device recall"
        private const val LEGACY_PHRASE = "mansion chef affair ancient announce police snap machine vanish liberty peace tennis effort recall law limit mosquito tornado toward advance vibrant bachelor auction voice"
    }
}
