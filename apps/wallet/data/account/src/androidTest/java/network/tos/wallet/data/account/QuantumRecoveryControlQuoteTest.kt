package network.tos.wallet.data.account

import androidx.test.ext.junit.runners.AndroidJUnit4
import network.tos.wallet.data.account.pq.QuantumRecoveryControlQuote
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.ton.boc.BagOfCells
import org.ton.cell.buildCell
import java.math.BigInteger
import java.security.MessageDigest

/** Pure execution-codec negative controls; these JSON objects do not mint native capabilities. */
@RunWith(AndroidJUnit4::class)
class QuantumRecoveryControlQuoteTest {
    private val message = BagOfCells(buildCell {}).toByteArray()
    private val address = "0:" + "11".repeat(32)
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun wire(): JSONObject {
        val root = hex(BagOfCells(message).roots.single().hash().toByteArray())
        val codec = JSONObject().put("account", address).put("type", "ordinary").put("aborted", false)
            .put("destroyed", false).put("other_fee_currencies", false).put("fees", "10")
            .put("compute", JSONObject().put("skipped", false).put("success", true).put("exit_code", 0))
            .put("in_msg", JSONObject().put("kind", "external_in").put("destination", address).put("hash", root))
            .put("out_msgs", JSONArray()).put("action", JSONObject.NULL)
        return JSONObject().put("status", "executed").put("message_root_hash", root)
            .put("message_bytes_hash", hex(MessageDigest.getInstance("SHA-256").digest(message)))
            .put("account", JSONObject().put("exists", true).put("address", address).put("observed_balance", "100"))
            .put("transaction", JSONObject().put("codec", codec).put("expected_balance", "90"))
    }
    @Test fun zeroOutputControlAccountsOnlyForFees() {
        assertEquals(BigInteger("100") to BigInteger("90"), QuantumRecoveryControlQuote.validateExecution(wire(), message, address))
        val action = JSONObject().put("success", true).put("valid", true).put("no_funds", false)
            .put("result_code", 0).put("skipped_actions", 0).put("messages_created", 0)
        val value = wire(); value.getJSONObject("transaction").getJSONObject("codec").put("action", action)
        QuantumRecoveryControlQuote.validateExecution(value, message, address)
        action.put("messages_created", 1)
        assertThrows(IllegalStateException::class.java) { QuantumRecoveryControlQuote.validateExecution(value, message, address) }
    }
    @Test fun hiddenOutputFailedComputeWrongMessageAndFeeMismatchAreRejected() {
        val output = wire()
        output.getJSONObject("transaction").getJSONObject("codec").getJSONArray("out_msgs").put(JSONObject().put("value", "1"))
        assertThrows(IllegalStateException::class.java) { QuantumRecoveryControlQuote.validateExecution(output, message, address) }
        val fee = wire(); fee.getJSONObject("transaction").getJSONObject("codec").put("fees", "9")
        assertThrows(IllegalStateException::class.java) { QuantumRecoveryControlQuote.validateExecution(fee, message, address) }
        val failed = wire(); failed.getJSONObject("transaction").getJSONObject("codec").getJSONObject("compute").put("success", false)
        assertThrows(IllegalStateException::class.java) { QuantumRecoveryControlQuote.validateExecution(failed, message, address) }
        val wrong = wire().put("message_bytes_hash", "00".repeat(32))
        assertThrows(IllegalStateException::class.java) { QuantumRecoveryControlQuote.validateExecution(wrong, message, address) }
    }
}
