package network.tos.wallet.data.account

import androidx.test.ext.junit.runners.AndroidJUnit4
import network.tos.wallet.data.account.pq.QuantumProbeFundingQuote
import network.tos.wallet.data.account.pq.QuantumPaymentQuote
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.ton.boc.BagOfCells
import org.ton.cell.buildCell
import java.math.BigInteger
import java.security.MessageDigest

/** Untrusted codec fixtures exercise rejection only; they do not create native quote capabilities. */
@RunWith(AndroidJUnit4::class)
class QuantumProbeFundingQuoteTest {
    private val message = BagOfCells(buildCell {}).toByteArray()
    private val source = "0:" + "11".repeat(32)
    private val destination = "0:" + "22".repeat(32)
    private val body = "33".repeat(32)
    private val amount = BigInteger("100")
    private fun wire(): JSONObject {
        val root = QuantumPaymentQuote.hex(BagOfCells(message).roots.single().hash().toByteArray())
        val output = JSONObject().put("kind", "internal").put("source", source).put("destination", destination)
            .put("value", "100").put("body_hash", body).put("bounce", false).put("bounced", false).put("other_currencies", false)
        val action = JSONObject().put("success", true).put("valid", true).put("no_funds", false)
            .put("result_code", 0).put("skipped_actions", 0).put("messages_created", 1)
        val codec = JSONObject().put("account", source).put("type", "ordinary").put("aborted", false)
            .put("destroyed", false).put("other_fee_currencies", false).put("fees", "10")
            .put("compute", JSONObject().put("skipped", false).put("success", true).put("exit_code", 0))
            .put("in_msg", JSONObject().put("kind", "external_in").put("destination", source).put("hash", root))
            .put("out_msgs", JSONArray().put(output)).put("action", action)
        return JSONObject().put("status", "executed").put("message_root_hash", root)
            .put("message_bytes_hash", QuantumPaymentQuote.hex(MessageDigest.getInstance("SHA-256").digest(message)))
            .put("account", JSONObject().put("exists", true).put("address", source).put("observed_balance", "1000"))
            .put("transaction", JSONObject().put("codec", codec).put("expected_balance", "890"))
    }
    private fun validate(value: JSONObject) = QuantumProbeFundingQuote.validateExecution(value, message, source, destination, body, amount)
    private fun codec(value: JSONObject) = value.getJSONObject("transaction").getJSONObject("codec")
    @Test fun exactOutputAllowsTransactionAndAdditionalForwardingFees() {
        assertEquals(BigInteger("1000") to BigInteger("890"), validate(wire()))
        val forwarded = wire(); forwarded.getJSONObject("transaction").put("expected_balance", "885")
        assertEquals(BigInteger("1000") to BigInteger("885"), validate(forwarded))
    }
    @Test fun changedOutputAndExtraOutputAreRejected() {
        for ((key, value) in listOf("source" to destination, "destination" to source, "kind" to "external_out",
            "value" to "101", "body_hash" to "44".repeat(32), "bounce" to true, "bounced" to true, "other_currencies" to true)) {
            val changed = wire(); codec(changed).getJSONArray("out_msgs").getJSONObject(0).put(key, value)
            assertThrows(IllegalStateException::class.java) { validate(changed) }
        }
        val extra = wire(); codec(extra).getJSONArray("out_msgs").put(JSONObject())
        assertThrows(IllegalStateException::class.java) { validate(extra) }
    }
    @Test fun failedActionAndWrongInputAreRejected() {
        for ((key, value) in listOf("success" to false, "valid" to false, "no_funds" to true,
            "result_code" to 1, "skipped_actions" to 1, "messages_created" to 0)) {
            val changed = wire(); codec(changed).getJSONObject("action").put(key, value)
            assertThrows(IllegalStateException::class.java) { validate(changed) }
        }
        val compute = wire(); codec(compute).getJSONObject("compute").put("success", false)
        assertThrows(IllegalStateException::class.java) { validate(compute) }
        val input = wire(); codec(input).getJSONObject("in_msg").put("hash", "00".repeat(32))
        assertThrows(IllegalStateException::class.java) { validate(input) }
        assertThrows(IllegalStateException::class.java) { validate(wire().put("message_bytes_hash", "00".repeat(32))) }
    }
    @Test fun insufficientDebitAndNoncanonicalAmountsAreRejected() {
        for (after in listOf("891", "901", "1001")) {
            val changed = wire(); changed.getJSONObject("transaction").put("expected_balance", after)
            assertThrows(IllegalStateException::class.java) { validate(changed) }
        }
        val noncanonical = wire(); codec(noncanonical).getJSONArray("out_msgs").getJSONObject(0).put("value", "0100")
        assertThrows(IllegalArgumentException::class.java) { validate(noncanonical) }
    }
}
