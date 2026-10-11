package network.tos.wallet.data.account.pq

import android.util.Base64
import network.tos.security.pq.QuantumProofTransport
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Raw read-only RPC relay. Endpoint replies never establish wallet authority. */
class QuantumProofHttpTransport(private val endpoint: HttpUrl, client: OkHttpClient) : QuantumProofTransport {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(20, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val sequence = AtomicLong()
    override fun query(request: ByteArray, maximumBytes: Int): ByteArray {
        require(request.size in 1..16384 && maximumBytes in 1..67_108_864)
        check(!Thread.currentThread().isInterrupted) { "Proof query cancelled" }
        val id = sequence.incrementAndGet()
        val body = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", "getProofQuery")
            .put("params", JSONObject().put("query", Base64.encodeToString(request, Base64.NO_WRAP))).toString()
        val query = Request.Builder().url(endpoint).post(body.toRequestBody("application/json".toMediaType())).build()
        client.newCall(query).execute().use { response ->
            check(response.isSuccessful) { "Proof response unavailable" }
            val payload = checkNotNull(response.body)
            val cap = ((maximumBytes.toLong() + 2) / 3 * 4 + 4096).toInt()
            check(payload.contentLength() <= cap) { "Proof response too large" }
            val bytes = ByteArrayOutputStream()
            payload.byteStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    check(!Thread.currentThread().isInterrupted) { "Proof query cancelled" }
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(count <= cap - bytes.size()) { "Proof response too large" }
                    bytes.write(buffer, 0, count)
                }
            }
            val text = bytes.toString("UTF-8")
            requireFlatResponse(text)
            val objectValue = JSONObject(text)
            val responseId = objectValue.opt("id")
            check(objectValue.opt("jsonrpc") == "2.0" && (responseId is Int || responseId is Long) &&
                  (responseId as Number).toLong() == id && !objectValue.has("error")) { "Proof RPC refused" }
            val encoded = objectValue.getJSONObject("result").getString("reply")
            check(encoded.length.toLong() <= (maximumBytes.toLong() + 2) / 3 * 4) { "Proof response too large" }
            val decoded = Base64.decode(encoded, Base64.NO_WRAP)
            check(decoded.isNotEmpty() && decoded.size <= maximumBytes) { "Proof response too large" }
            return decoded
        }
    }
    private fun requireFlatResponse(text: String) {
        var depth = 0; var quoted = false; var escaped = false; var started = false; var finished = false
        for (character in text) {
            if (!quoted && depth == 0) {
                if (character == ' ' || character == '\t' || character == '\r' || character == '\n') continue
                check(!finished && !started && character == '{') { "Proof response malformed" }
                started = true
            }
            if (quoted) {
                if (escaped) escaped = false else if (character == '\\') escaped = true else if (character == '"') quoted = false
            } else when (character) {
                '"' -> quoted = true
                '{' -> { depth++; check(depth <= 2) { "Proof response nesting refused" } }
                '}' -> { depth--; check(depth >= 0) { "Proof response malformed" }; if (depth == 0) finished = true }
                '[', ']' -> error("Proof response arrays refused")
            }
        }
        check(started && finished && !quoted && depth == 0) { "Proof response malformed" }
    }
}
