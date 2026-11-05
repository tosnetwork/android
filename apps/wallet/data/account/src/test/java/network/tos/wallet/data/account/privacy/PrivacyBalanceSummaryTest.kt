package network.tos.wallet.data.account.privacy

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PrivacyBalanceSummaryTest {
    private fun value() = JSONObject().put("version", 1).put("total_outputs", "13").put("owned_outputs", "7")
        .put("unique_unspent_notes", "4").put("duplicate_outputs", "2").put("reused_indices_count", "1")
        .put("diagnostics_count", "0").put("unspent_balance", "340282366920938463463374607431768211455")
        .put("scan_next_index", "18446744073709551615")
    private fun parse(value: JSONObject) = PrivacyBalanceSummary.parse(value.toString().toByteArray())
    @Test fun preservesFullUnsignedWidthsAndNullableScanIndexWithoutAllocatingAnything() {
        val summary = parse(value())
        assertEquals("340282366920938463463374607431768211455", summary.unspentBalance)
        assertEquals("18446744073709551615", summary.scanNextIndex)
        assertNull(parse(value().put("scan_next_index", JSONObject.NULL)).scanNextIndex)
        assertEquals("13", summary.totalOutputs)
    }
    @Test fun rejectsNumericCoercionsNoncanonicalAndOverflowValues() {
        for (wrong in listOf<Any>(1L, 1.5, "01", "+1", "-1", "1e3", " 1", "18446744073709551616")) {
            assertThrows(IllegalArgumentException::class.java) { parse(value().put("owned_outputs", wrong)) }
        }
        assertThrows(IllegalArgumentException::class.java) { parse(value().put("unspent_balance", "340282366920938463463374607431768211456")) }
        assertThrows(IllegalArgumentException::class.java) { parse(value().put("total_outputs", "32769")) }
    }
    @Test fun batchResponseMissingFieldsAndNewVersionAreNotZeroBalance() {
        assertThrows(IllegalArgumentException::class.java) { parse(value().put("batch_unspent_balance", "0")) }
        assertThrows(IllegalArgumentException::class.java) { parse(value().put("version", 2)) }
        assertThrows(IllegalArgumentException::class.java) { parse(value().also { it.remove("unspent_balance") }) }
    }
    @Test fun rejectsTrailingInvalidUTF8AndOversizeNativeOutput() {
        assertThrows(IllegalArgumentException::class.java) { PrivacyBalanceSummary.parse((value().toString() + " {}").toByteArray()) }
        assertThrows(java.nio.charset.CharacterCodingException::class.java) { PrivacyBalanceSummary.parse(byteArrayOf(0xc0.toByte(), 0x80.toByte())) }
        assertThrows(IllegalArgumentException::class.java) { PrivacyBalanceSummary.parse(ByteArray(4097)) }
    }
}
