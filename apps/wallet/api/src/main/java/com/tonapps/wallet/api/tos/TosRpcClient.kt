package com.tonapps.wallet.api.tos

import androidx.collection.ArrayMap
import com.tonapps.network.postJSON
import okhttp3.OkHttpClient
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
 * The base url comes from the Constants TOS endpoints; point it at http://127.0.0.1:<port>
 * when running a local node.
 */
class TosRpcException(val code: Int, message: String) : Exception(message)

class TosRpcClient(
    private val httpClient: OkHttpClient,
    /** (testnet) -> base url, e.g. https://rpc.tos.network or http://127.0.0.1:18545 */
    private val baseUrlProvider: (Boolean) -> String,
    private val apiKeyProvider: () -> String? = { null },
) {

    private val idCounter = AtomicLong(0)

    /** Returns the raw result value (may be a JSONObject or a JSONArray). */
    fun callRaw(method: String, params: JSONObject = JSONObject(), testnet: Boolean = false): Any {
        val endpoint = baseUrlProvider(testnet).trimEnd('/') + "/jsonRPC"
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", idCounter.incrementAndGet())
            .put("method", method)
            .put("params", params)

        val headers = ArrayMap<String, String>()
        apiKeyProvider()?.takeIf { it.isNotBlank() }?.let { headers["X-API-Key"] = it }

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
