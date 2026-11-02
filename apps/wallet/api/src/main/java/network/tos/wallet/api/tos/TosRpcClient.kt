package network.tos.wallet.api.tos

import androidx.collection.ArrayMap
import network.tos.network.postJSON
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * TOS node JSON-RPC client (Phase 1 skeleton).
 *
 * Wire format (toncenter v2 / tonlib style, see validator-engine json-rpc-server):
 *   POST {baseUrl}/jsonRPC
 *   request body: { "jsonrpc":"2.0", "id":<n>, "method":<m>, "params":{...} }
 *   success:      { "ok":true,  "jsonrpc":"2.0", "id":<n>, "result":<obj|array> }
 *   failure:      { "ok":false, "jsonrpc":"2.0", "id":<n>, "error":"...", "code":-32xxx }
 *
 * The base URL provider is evaluated for every request so a newly selected node takes
 * effect immediately without restarting the app.
 */
class TosRpcException(val code: Int, message: String) : Exception(message)

class TosRpcClient(
    private val httpClient: OkHttpClient,
    /** (testnet) -> base URL or full supported JSON-RPC URL. */
    private val baseUrlProvider: (Boolean) -> String,
    private val apiKeyProvider: () -> String? = { null },
    private val endpointConfigProvider: ((Boolean) -> Pair<String, String?>)? = null,
) {

    private val idCounter = AtomicLong(0)

    /** Returns the raw result value (may be a JSONObject or a JSONArray). */
    fun callRaw(method: String, params: JSONObject = JSONObject(), testnet: Boolean = false): Any {
        val (baseUrl, apiKey) = endpointConfigProvider?.invoke(testnet)
            ?: (baseUrlProvider(testnet) to apiKeyProvider())
        val endpoint = TosRpcEndpoint.jsonRpcUrl(baseUrl)
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", idCounter.incrementAndGet())
            .put("method", method)
            .put("params", params)

        val headers = ArrayMap<String, String>()
        apiKey?.takeIf { it.isNotBlank() }?.let { headers["X-API-Key"] = it }

        val response = httpClient.postJSON(endpoint, payload.toString(), headers)
        val text = response.body?.string() ?: throw TosRpcException(-32603, "Empty RPC response")
        val json = JSONObject(text)

        // TVM convention: {ok:false, error, code}
        if (json.has("ok") && !json.optBoolean("ok", false)) {
            throw TosRpcException(json.optInt("code", -32603), json.optString("error", "RPC error"))
        }
        // Ethereum convention: {error:{code,message}}
        json.opt("error")?.let { err ->
            if (err is JSONObject) {
                throw TosRpcException(err.optInt("code", -32603), err.optString("message", "RPC error"))
            } else if (err != JSONObject.NULL && err.toString().isNotBlank()) {
                throw TosRpcException(json.optInt("code", -32603), err.toString())
            }
        }
        return json.opt("result") ?: JSONObject()
    }

    /** Bounded discovery/metadata only. The returned object is not a balance proof. */
    fun callTokenRead(method: String, params: JSONObject, testnet: Boolean = false): JSONObject {
        require(method in setOf("getAccountTokens", "getTokenData"))
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Token read cancelled")
        val (baseUrl, apiKey) = endpointConfigProvider?.invoke(testnet)
            ?: (baseUrlProvider(testnet) to apiKeyProvider())
        val id = idCounter.incrementAndGet()
        val body = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params).toString()
        require(body.toByteArray(Charsets.UTF_8).size <= 16_384)
        val request = Request.Builder().url(TosRpcEndpoint.jsonRpcUrl(baseUrl))
            .post(body.toRequestBody("application/json".toMediaType())).apply {
                apiKey?.takeIf { it.isNotBlank() }?.let { header("X-API-Key", it) }
            }.build()
        val boundedClient = httpClient.newBuilder().followRedirects(false).followSslRedirects(false)
            .callTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
        boundedClient.newCall(request).execute().use { response ->
            check(response.isSuccessful)
            val payload = checkNotNull(response.body)
            val cap = 2_097_152
            check(payload.contentLength() <= cap)
            val bytes = ByteArrayOutputStream()
            payload.byteStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("Token read cancelled")
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(count <= cap - bytes.size())
                    bytes.write(buffer, 0, count)
                }
            }
            val text = bytes.toString("UTF-8")
            val stack = ArrayList<Char>()
            var quoted = false; var escaped = false; var finished = false
            for (character in text) {
                if (!quoted && stack.isEmpty() && !character.isWhitespace()) check(!finished && character == '{')
                if (quoted) {
                    if (escaped) escaped = false else if (character == '\\') escaped = true else if (character == '"') quoted = false
                } else when (character) {
                    '"' -> quoted = true
                    '{', '[' -> { stack.add(character); check(stack.size <= 64) }
                    '}', ']' -> {
                        check(stack.isNotEmpty() && stack.removeAt(stack.lastIndex) == if (character == '}') '{' else '[')
                        if (stack.isEmpty()) finished = true
                    }
                }
            }
            check(finished && stack.isEmpty() && !quoted)
            val envelope = JSONObject(text)
            val replyId = envelope.opt("id")
            check(envelope.opt("jsonrpc") == "2.0" && (replyId is Int || replyId is Long) &&
                (replyId as Number).toLong() == id && !envelope.has("error") &&
                (!envelope.has("ok") || envelope.opt("ok") == true))
            return envelope.getJSONObject("result")
        }
    }

    fun callObject(method: String, params: JSONObject = JSONObject(), testnet: Boolean = false): JSONObject {
        return when (val result = callRaw(method, params, testnet)) {
            is JSONObject -> result
            else -> throw TosRpcException(-32603, "Expected object result from $method")
        }
    }

    fun callArray(method: String, params: JSONObject = JSONObject(), testnet: Boolean = false): JSONArray {
        return when (val result = callRaw(method, params, testnet)) {
            is JSONArray -> result
            is JSONObject -> result.optJSONArray("transactions") ?: JSONArray()
            else -> throw TosRpcException(-32603, "Expected array result from $method")
        }
    }
}
