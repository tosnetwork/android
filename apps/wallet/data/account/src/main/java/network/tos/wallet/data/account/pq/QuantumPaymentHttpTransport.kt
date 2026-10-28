package network.tos.wallet.data.account.pq

import android.util.Base64
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Broadcast acceptance is not inclusion. No caller may infer a payment result here. */
class QuantumPaymentHttpTransport(private val endpoint: HttpUrl, client: OkHttpClient) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(20, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    fun send(bytes: ByteArray) {
        require(bytes.size in 1..1_048_576)
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Broadcast cancelled")
        val id = UUID.randomUUID().toString()
        val payload = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", "sendBocReturnHash")
            .put("params", JSONObject().put("boc", Base64.encodeToString(bytes, Base64.NO_WRAP)))
        val request = Request.Builder().url(endpoint).post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful)
            val body = checkNotNull(response.body); check(body.contentLength() <= 65536)
            val output = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("Broadcast cancelled")
                    val n = input.read(buffer); if (n < 0) break
                    check(n <= 65536 - output.size()); output.write(buffer, 0, n)
                }
            }
            val text = output.toString("UTF-8")
            var quoted = false; var escaped = false; var depth = 0
            for (c in text) {
                if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
                else when (c) { '"' -> quoted = true; '{', '[' -> { depth++; check(depth <= 16) }; '}', ']' -> { depth--; check(depth >= 0) } }
            }
            check(!quoted && depth == 0)
            val value = JSONObject(text)
            check(value.opt("jsonrpc") == "2.0" && value.opt("id") == id && !value.has("error") && !value.isNull("result"))
        }
    }
}
