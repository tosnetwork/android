package network.tos.wallet.data.account.pq

import android.util.Base64
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Bounded raw-cell transport. Metadata is discarded before native verification. */
class QuantumHistoryHttpTransport(private val endpoint: HttpUrl, client: OkHttpClient) : QuantumHistoryReader.Query {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    private val sequence = AtomicLong()
    override fun transaction(address: String, logicalTime: String, hash: String): String {
        require(address.matches(Regex("0:[0-9a-f]{64}")) && QuantumHistoryTransaction.time(logicalTime) > 0uL)
        val expected = Base64.encodeToString(QuantumHistoryTransaction.hex(hash), Base64.NO_WRAP)
        if (Thread.currentThread().isInterrupted) throw InterruptedException("History transport cancelled")
        val id = sequence.incrementAndGet()
        val body = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", "getTransactions")
            .put("params", JSONObject().put("address", address).put("limit", 1).put("lt", logicalTime).put("hash", expected))
        val request = Request.Builder().url(endpoint).post(body.toString().toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful)
            val payload = checkNotNull(response.body)
            val cap = 2 * 1024 * 1024
            check(payload.contentLength() <= cap)
            val bytes = ByteArrayOutputStream()
            payload.byteStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("History transport cancelled")
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(count <= cap - bytes.size())
                    bytes.write(buffer, 0, count)
                }
            }
            val text = bytes.toString("UTF-8")
            requireBoundedJSON(text)
            val value = JSONObject(text)
            val replyID = value.opt("id")
            check(value.opt("jsonrpc") == "2.0" && (replyID is Int || replyID is Long) &&
                (replyID as Number).toLong() == id && !value.has("error"))
            val items = value.getJSONArray("result")
            check(items.length() == 1)
            return items.getJSONObject(0).getString("data").also { check(it.length in 1..1_398_104) }
        }
    }
    private fun requireBoundedJSON(text: String) {
        var depth = 0; var quoted = false; var escaped = false; var finished = false
        for (character in text) {
            if (!quoted && depth == 0 && !character.isWhitespace()) {
                check(!finished && character == '{')
            }
            if (quoted) {
                if (escaped) escaped = false else if (character == '\\') escaped = true else if (character == '"') quoted = false
            } else when (character) {
                '"' -> quoted = true
                '{', '[' -> { depth++; check(depth <= 64) }
                '}', ']' -> { depth--; check(depth >= 0); if (depth == 0) finished = true }
            }
        }
        check(finished && depth == 0 && !quoted)
    }
}
