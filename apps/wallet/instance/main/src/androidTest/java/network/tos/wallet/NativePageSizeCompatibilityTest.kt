package network.tos.wallet

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import network.tos.security.Sodium
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.zip.ZipFile

/** Run unchanged on both 4KB and 16KB devices; record the actual kernel page size. */
@RunWith(AndroidJUnit4::class)
class NativePageSizeCompatibilityTest {
    @Test
    fun everyPackagedNativeLibraryLoads() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pageSize = Os.sysconf(OsConstants._SC_PAGESIZE)
        assertTrue("Unexpected kernel page size: $pageSize", pageSize == 4096L || pageSize == 16384L)
        val abi = Build.SUPPORTED_ABIS.first()
        val libraries = ZipFile(context.applicationInfo.sourceDir).use { apk ->
            apk.entries().asSequence().map { it.name }
                .filter { it.startsWith("lib/$abi/") && it.endsWith(".so") }
                .map { it.substringAfterLast('/').removePrefix("lib").removeSuffix(".so") }
                .toList()
        }
        assertTrue("Sodium JNI wrapper is missing", "libsodium" in libraries)
        assertTrue("Blur JNI library is missing", "renderscript-toolkit" in libraries)
        libraries.forEach { library ->
            System.loadLibrary(library)
            Log.i("TOSNativePages", "Loaded $library on $abi with PAGE_SIZE=$pageSize")
        }
    }

    @Test
    fun nativeEncryptionRoundTripsAndRejectsTampering() {
        assertTrue(Sodium.init() >= 0)
        val plaintext = "PUBLIC TEST DATA: TOS native page compatibility".toByteArray()
        val key = ByteArray(32) { (it + 1).toByte() }
        val nonce = ByteArray(Sodium.cryptoBoxNonceBytes()) { it.toByte() }
        val encrypted = Sodium.cryptoSecretbox(plaintext, nonce, key)
        assertNotNull(encrypted)
        val ciphertext = requireNotNull(encrypted)
        assertEquals(plaintext.size + Sodium.cryptoBoxMacBytes(), ciphertext.size)
        assertArrayEquals(plaintext, Sodium.cryptoSecretboxOpen(ciphertext.copyOf(), nonce.copyOf(), key.copyOf()))
        val tampered = ciphertext.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }
        assertThrows(SecurityException::class.java) {
            Sodium.cryptoSecretboxOpen(tampered, nonce.copyOf(), key.copyOf())
        }
        assertThrows(SecurityException::class.java) {
            Sodium.cryptoSecretboxOpen(ciphertext.copyOf(), nonce.copyOf(), ByteArray(32))
        }
    }

    @Test
    fun nativeBlurProcessesBitmapPixels() {
        val input = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val output = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try {
            input.eraseColor(Color.BLACK)
            input.setPixel(8, 8, Color.WHITE)
            // Exercise the same JNI entry point used by pre-API31 app blur rendering.
            val toolkit = Class.forName("blur.Toolkit")
            toolkit.getMethod("blur", Bitmap::class.java, Bitmap::class.java, Int::class.javaPrimitiveType)
                .invoke(toolkit.getField("INSTANCE").get(null), input, output, 3)
            assertTrue("Blur must reduce the impulse", Color.red(output.getPixel(8, 8)) in 1..254)
            assertTrue("Blur must reach neighboring pixels", Color.red(output.getPixel(8, 7)) in 1..254)
            assertEquals(255, Color.alpha(output.getPixel(8, 8)))
        } finally {
            input.recycle()
            output.recycle()
        }
    }
}
