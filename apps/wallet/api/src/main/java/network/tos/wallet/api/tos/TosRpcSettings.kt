package network.tos.wallet.api.tos

import android.content.Context
import java.net.URI

/** Persistent, user-selected TOS JSON-RPC endpoint. */
class TosRpcSettings(context: Context) {

    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    val customEndpoint: String?
        get() = preferences.getString(ENDPOINT_KEY, null)
            ?.let(TosRpcEndpoint::normalizeOrNull)

    fun setEndpoint(value: String): String {
        val endpoint = TosRpcEndpoint.normalizeOrNull(value)
            ?: throw IllegalArgumentException("Enter a valid HTTP or HTTPS RPC address")
        preferences.edit().putString(ENDPOINT_KEY, endpoint).apply()
        return endpoint
    }

    fun reset() {
        preferences.edit().remove(ENDPOINT_KEY).apply()
    }

    private companion object {
        private const val PREFERENCES_NAME = "tos_rpc_settings"
        private const val ENDPOINT_KEY = "custom_endpoint"
    }
}

/** Normalizes a node address to the base URL expected by [TosRpcClient]. */
object TosRpcEndpoint {

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
