package network.tos.wallet.data.account.pq

import android.util.Base64
import network.tos.security.pq.QuantumWalletTransactionNative
import org.json.JSONObject
import java.math.BigInteger
import java.util.Collections

/** Native typed transaction, never a relay event summary. Inclusion requires a
 * head from an authenticated account proof and every committed predecessor.
 */
class QuantumHistoryTransaction private constructor(
    val account: String,
    val hash: String,
    val logicalTime: String,
    val previousHash: String,
    val previousLogicalTime: String,
    val timestamp: Long,
    val feesNano: String,
    val otherFeeCurrencies: Boolean,
    val inbound: Message?,
    val outbound: List<Message>,
    val successfulExecution: Boolean,
    val skippedActions: Long,
    val messagesCreated: Long?,
    val direction: Direction,
    val outcome: Outcome,
) {
    data class Message(val kind: String, val hash: String, val bodyHash: String,
        val source: String?, val destination: String?, val amountNano: String?,
        val otherCurrencies: Boolean, val bounced: Boolean, val bounce: Boolean?)
    enum class Direction { Incoming, Outgoing, Operation }
    enum class Outcome { Included, Executed, SendSkipped, ExecutionFailed }

    internal fun matchesPayment(externalHash: String, intent: network.tos.blockchain.ton.contract.QuantumPaymentIntent): Boolean =
        successfulExecution && !otherFeeCurrencies && skippedActions == 0L && messagesCreated == 1L &&
        inbound?.kind == "external_in" && inbound.hash == externalHash && inbound.destination == account &&
        outbound.size == 1 && outbound.single().let { out ->
            out.kind == "internal" && out.source == account && out.destination == QuantumAccountReader.rawAddressFor(intent.recipient) &&
                !out.otherCurrencies && !out.bounced && out.bounce == intent.bounce && out.amountNano == intent.amount.toString() &&
                out.bodyHash == QuantumPaymentQuote.hex(intent.body.hash().toByteArray())
        }

    companion object {
        private val hashPattern = Regex("[0-9a-f]{64}")
        internal fun hex(value: String): ByteArray {
            require(value.matches(hashPattern))
            return value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
        internal fun time(value: String): ULong {
            require(value.matches(Regex("0|[1-9][0-9]{0,19}")))
            return requireNotNull(value.toULongOrNull())
        }
        private fun coins(value: String): String {
            require(value.length in 1..37 && value.matches(Regex("0|[1-9][0-9]*")))
            require(BigInteger(value).bitLength() <= 120)
            return value
        }
        private fun message(value: JSONObject): Message {
            val kind = value.getString("kind")
            require(kind in listOf("internal", "external_in", "external_out"))
            val hash = value.getString("hash").also { hex(it) }
            val body = value.getString("body_hash").also { hex(it) }
            fun optional(key: String) = if (value.isNull(key)) null else value.getString(key)
            val source = optional("source"); val destination = optional("destination")
            for (address in listOfNotNull(source, destination)) require(address.matches(Regex("-?[0-9]+:[0-9a-f]{64}")))
            val amount = optional("value")?.let(::coins)
            require((kind == "internal") == (amount != null))
            return Message(kind, hash, body, source, destination, amount,
                if (kind == "internal") value.getBoolean("other_currencies") else false,
                if (kind == "internal") value.getBoolean("bounced") else false,
                if (kind == "internal") value.getBoolean("bounce") else null)
        }
        fun formatNano(value: String): String? {
            if (runCatching { coins(value) }.isFailure) return null
            val padded = value.padStart(10, '0')
            val whole = padded.dropLast(9)
            val fraction = padded.takeLast(9).trimEnd('0')
            return if (fraction.isEmpty()) "$whole TOS" else "$whole.$fraction TOS"
        }
        internal fun verify(account: String, logicalTime: String, expectedHash: String, encoded: String): QuantumHistoryTransaction {
            require(account.matches(Regex("0:[0-9a-f]{64}")) && time(logicalTime) > 0uL)
            require(encoded.length in 1..1_398_104)
            val boc = Base64.decode(encoded, Base64.NO_WRAP)
            val value = JSONObject(QuantumWalletTransactionNative.verify(0, hex(account.substring(2)), logicalTime,
                hex(expectedHash), boc).toString(Charsets.UTF_8))
            require(value.getString("interface") == "tos-wallet-transaction/1" &&
                value.getString("account") == account && value.getString("hash") == expectedHash &&
                value.getString("lt") == logicalTime)
            val previousHash = value.getString("previous_hash").also { hex(it) }
            val previousTime = value.getString("previous_lt")
            require(time(previousTime) < time(logicalTime) && (previousTime == "0") == (previousHash == "00".repeat(32)))
            val timestamp = value.getLong("timestamp").also { require(it in 0..0xffffffffL) }
            val fees = coins(value.getString("fees"))
            val input = if (value.isNull("in_msg")) null else message(value.getJSONObject("in_msg"))
            val outputs = value.getJSONArray("out_msgs")
            require(outputs.length() <= 256)
            val messages = Collections.unmodifiableList((0 until outputs.length()).map { message(outputs.getJSONObject(it)) })
            val ordinary = value.getString("type") == "ordinary"
            require(ordinary || value.getString("type") == "other")
            val compute = value.optJSONObject("compute"); val action = value.optJSONObject("action")
            val success = ordinary && compute != null && !compute.getBoolean("skipped") && compute.getBoolean("success") &&
                compute.getInt("exit_code") in 0..1 && !value.getBoolean("aborted") && !value.getBoolean("destroyed") &&
                (action == null || (action.getBoolean("success") && action.getBoolean("valid") &&
                    !action.getBoolean("no_funds") && action.getInt("result_code") == 0))
            val skipped = (action?.getLong("skipped_actions") ?: 0L).also { require(it in 0..65535L) }
            val direction = when {
                input?.kind == "internal" && input.destination == account -> Direction.Incoming
                messages.any { it.kind == "internal" && it.source == account && !it.bounced } -> Direction.Outgoing
                else -> Direction.Operation
            }
            // Compute-skipped incoming messages can credit undeployed accounts.
            val outcome = when {
                direction != Direction.Operation -> Outcome.Included
                success && skipped > 0 -> Outcome.SendSkipped
                success -> Outcome.Executed
                ordinary -> Outcome.ExecutionFailed
                else -> Outcome.Included
            }
            return QuantumHistoryTransaction(account, expectedHash, logicalTime, previousHash, previousTime,
                timestamp, fees, value.getBoolean("other_fee_currencies"), input, messages, success, skipped, action?.getLong("messages_created"), direction, outcome)
        }
    }
}
