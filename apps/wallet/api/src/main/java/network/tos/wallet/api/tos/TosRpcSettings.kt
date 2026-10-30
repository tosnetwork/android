package network.tos.wallet.api.tos

import android.content.Context
import java.net.URI
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Persistent, user-selected TOS JSON-RPC endpoint. */
class TosRpcSettings(context: Context) {

    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _endpointFlow = MutableStateFlow(customEndpoint)
    val endpointFlow = _endpointFlow.asStateFlow()

    val customEndpoint: String?
        get() = preferences.getString(ENDPOINT_KEY, null)
            ?.let(TosRpcEndpoint::normalizeOrNull)

    fun setEndpoint(value: String): String {
        val endpoint = TosRpcEndpoint.normalizeOrNull(value)
            ?: throw IllegalArgumentException("Enter a valid HTTP or HTTPS RPC address")
        preferences.edit().putString(ENDPOINT_KEY, endpoint).apply()
        _endpointFlow.value = endpoint
        return endpoint
    }

    fun reset() {
        preferences.edit().remove(ENDPOINT_KEY).apply()
        _endpointFlow.value = null
    }

    private companion object {
        private const val PREFERENCES_NAME = "tos_rpc_settings"
        private const val ENDPOINT_KEY = "custom_endpoint"
    }
}

/** Normalizes a node address while preserving an explicitly supplied supported RPC route. */
object TosRpcEndpoint {

    /** Appends the TOS JSON-RPC route unless the configured URL already names the documented route. */
    fun jsonRpcUrl(baseUrl: String): String {
        val endpoint = baseUrl.trimEnd('/')
        return if (endpoint.endsWith("/json_rpc", ignoreCase = true) ||
            endpoint.endsWith("/jsonRPC", ignoreCase = true)) endpoint
        else "$endpoint/jsonRPC"
    }

    fun normalizeOrNull(value: String): String? {
        var candidate = value.trim()
        if (candidate.isEmpty()) return null
        if (!candidate.contains("://")) candidate = "http://$candidate"

        candidate = candidate.trimEnd('/')
        if (candidate.endsWith("/jsonRPC", ignoreCase = true)) {
            candidate = candidate.dropLast("/jsonRPC".length).trimEnd('/')
        }

        return try {
            val uri = URI(candidate)
            if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
            if (uri.host.isNullOrBlank() || uri.userInfo != null) return null
            if (uri.rawQuery != null || uri.rawFragment != null) return null
            if (uri.port !in -1..65535) return null
            uri.toASCIIString().trimEnd('/')
        } catch (_: Exception) {
            null
        }
    }
}
