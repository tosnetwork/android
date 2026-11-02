package network.tos.security.pq

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuantumWalletTransactionDeviceTest {
    private val assets get() = InstrumentationRegistry.getInstrumentation().context.assets
    private fun read(name: String) = assets.open("quantum-transactions/$name").use { it.readBytes() }
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun refused(block: () -> Unit): IllegalArgumentException {
        try { block(); fail("Unbound transaction accepted") } catch (error: IllegalArgumentException) { return error }
        error("Expected refusal")
    }

    @Test fun nativeBoundaryRejectsInvalidInputsWithoutKotlinPreconditions() {
        val manifest = JSONObject(read("manifest.json").toString(Charsets.UTF_8))
        val item = manifest.getJSONArray("transactions").getJSONObject(0)
        val account = hex(manifest.getString("account")); val hash = hex(item.getString("hash"))
        val boc = read(item.getString("file"))
        val native = QuantumWalletTransactionNative::class.java.getDeclaredMethod("nativeVerify",
            Int::class.javaPrimitiveType, ByteArray::class.java, String::class.java,
            ByteArray::class.java, ByteArray::class.java).also { it.isAccessible = true }
        fun reject(workchain: Int = 0, a: ByteArray? = account, time: String? = item.getString("lt"),
                   h: ByteArray? = hash, bytes: ByteArray? = boc) {
            try {
                native.invoke(QuantumWalletTransactionNative, workchain, a, time, h, bytes)
                fail("Native JNI bounds accepted")
            } catch (failure: java.lang.reflect.InvocationTargetException) {
                assertTrue(failure.targetException is IllegalArgumentException)
            }
        }
        reject(workchain = -1); reject(a = null); reject(a = ByteArray(31)); reject(h = null)
        reject(bytes = null); reject(bytes = byteArrayOf()); reject(bytes = ByteArray(1_048_577))
        reject(time = null)
        for (time in listOf("", "0", "01", "-1", "١", "18446744073709551616")) reject(time = time)
    }

    @Test fun authenticatedHeadIntegerParsingPreservesFullUnsignedRange() {
        fun parse(value: String) = QuantumVerifiedRead.exactTransactionHeadTime(
            "{\"account\":{\"last_trans_lt\":$value}}".toByteArray())
        for (value in listOf("0", "9223372036854775808", "18446744073709551615")) assertEquals(value, parse(value))
        for (value in listOf("18446744073709551616", "-1", "1.0", "1e3", "\"123\"", "true")) {
            try { parse(value); fail("Invalid proven logical time accepted: $value") } catch (_: IllegalStateException) { }
        }
    }

    @Test fun frozenNativeChainPreservesOpaqueBodyAndActualOutgoingMessages() {
        val manifest = JSONObject(read("manifest.json").toString(Charsets.UTF_8))
        val account = manifest.getString("account")
        val items = manifest.getJSONArray("transactions")
        var expectedHash = manifest.getString("proven_head")
        var expectedTime = "13000001"
        for (index in 0 until items.length()) {
            val item = items.getJSONObject(index)
            val boc = read(item.getString("file"))
            assertEquals(item.getString("sha256"), digest(boc))
            assertEquals(expectedHash, item.getString("hash"))
            assertEquals(expectedTime, item.getString("lt"))
            val result = JSONObject(QuantumWalletTransactionNative.verify(0, hex(account), expectedTime,
                hex(expectedHash), boc).toString(Charsets.UTF_8))
            assertEquals("tos-wallet-transaction/1", result.getString("interface"))
            assertEquals("0:$account", result.getString("account"))
            assertEquals(expectedHash, result.getString("hash"))
            assertEquals(expectedTime, result.getString("lt"))
            val outgoing = result.getJSONArray("out_msgs")
            val amounts = item.getJSONArray("outgoing_amounts")
            assertEquals(amounts.length(), outgoing.length())
            for (message in 0 until outgoing.length()) {
                assertEquals(amounts.getString(message), outgoing.getJSONObject(message).getString("value"))
            }
            assertEquals(item.getInt("skipped_actions"), result.getJSONObject("action").getInt("skipped_actions"))
            if (index == 0) {
                assertTrue(result.getJSONObject("compute").getBoolean("success"))
                assertTrue(result.getJSONObject("action").getBoolean("success"))
                assertEquals(0, outgoing.length()) // Successful request skipped the +2 transfer.
            }
            expectedHash = result.getString("previous_hash")
            expectedTime = result.getString("previous_lt")
        }
        assertEquals("00".repeat(32), expectedHash)
        assertEquals("0", expectedTime)
    }

    @Test fun rejectsWrongBindingsCorruptionBoundsAndUnsignedOverflow() {
        val manifest = JSONObject(read("manifest.json").toString(Charsets.UTF_8))
        val item = manifest.getJSONArray("transactions").getJSONObject(0)
        val account = hex(manifest.getString("account"))
        val hash = hex(item.getString("hash"))
        val boc = read(item.getString("file"))
        fun check(a: ByteArray = account, time: String = item.getString("lt"),
                  h: ByteArray = hash, bytes: ByteArray = boc) =
            QuantumWalletTransactionNative.verify(0, a, time, h, bytes)
        refused { check(a = account.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }) }
        refused { check(h = hash.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }) }
        refused { check(time = "13000002") }
        refused { check(bytes = boc.copyOf(boc.size - 1)) }
        refused { check(bytes = boc.copyOf().also { it[0] = 0 }) }
        refused { check(bytes = ByteArray(1_048_577)) }
        refused { check(a = ByteArray(31)) }
        for (time in listOf("0", "01", "-1", "١", "18446744073709551616")) refused { check(time = time) }
        // Full unsigned range reaches native hash/time verification, without a signed Long conversion.
        assertEquals("Transaction verification refused", refused { check(time = "18446744073709551615") }.message)
        // Even caller-aliased inputs become disjoint owned C ABI byte ranges.
        refused { check(a = hash, h = hash) }
    }
}
