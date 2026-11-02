package network.tos.wallet.api.tos

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Issuer-controlled display metadata, never balance or signing authority. */
data class TosTokenOffchainMetadata(val name: String?, val symbol: String?, val decimals: Int) {
    companion object {
        const val MAXIMUM_BYTES = 65_536

        fun url(uri: String): HttpUrl {
            require(uri.toByteArray(Charsets.UTF_8).size in 1..2048)
            val text = if (uri.startsWith("ipfs://")) "https://ipfs.io/ipfs/" + uri.removePrefix("ipfs://") else uri
            val url = text.toHttpUrl()
            require(url.scheme == "https" && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null)
            return url
        }

        fun decode(bytes: ByteArray): TosTokenOffchainMetadata {
            require(bytes.size in 1..MAXIMUM_BYTES)
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            val stack = ArrayList<Char>()
            var quoted = false; var escaped = false; var started = false; var finished = false
            for (c in text) {
                if (!quoted && stack.isEmpty()) {
                    if (c in " \t\r\n") continue
                    require(!started && !finished && c == '{'); started = true
                }
                if (quoted) {
                    if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
                } else when (c) {
                    '"' -> quoted = true
                    '{', '[' -> { stack.add(c); require(stack.size <= 8) }
                    '}', ']' -> {
                        require(stack.isNotEmpty() && stack.removeAt(stack.lastIndex) == if (c == '}') '{' else '[')
                        if (stack.isEmpty()) finished = true
                    }
                }
            }
            require(started && finished && !quoted && stack.isEmpty())
            // JSONObject accepts non-JSON quoted keys; those can disagree with
            // the scanner's string boundaries and conceal excessive nesting.
            require(Json.parseToJsonElement(text) is JsonObject)
            val json = JSONObject(text)
            fun field(key: String, cap: Int): String? {
                val value = json.opt(key)
                if (value == null || value == JSONObject.NULL) return null
                require(value is String && value.toByteArray(Charsets.UTF_8).size <= cap)
                return value.takeIf { it.isNotEmpty() }
            }
            val precision = if (!json.has("decimals")) 9 else {
                val raw = json.get("decimals")
                require(raw is String || raw is Int || raw is Long)
                val value = raw.toString()
                require(value.matches(Regex("[0-9]{1,3}")))
                value.toInt().also { require(it <= 255) }
            }
            return TosTokenOffchainMetadata(field("name", 128), field("symbol", 32), precision)
        }
    }
}

/** No RPC headers, cookies, cache, credentials or executable content. */
class TosTokenOffchainMetadataClient internal constructor(http: OkHttpClient) {
    constructor() : this(OkHttpClient())
    private val client = http.newBuilder().followRedirects(false).followSslRedirects(false)
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .cache(null).callTimeout(3, TimeUnit.SECONDS).connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS).build()

    fun read(uri: String): TosTokenOffchainMetadata {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Token metadata read cancelled")
        val request = Request.Builder().url(TosTokenOffchainMetadata.url(uri)).header("Accept", "application/json").get().build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful)
            val body = checkNotNull(response.body)
            require(body.contentLength() <= TosTokenOffchainMetadata.MAXIMUM_BYTES)
            val out = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("Token metadata read cancelled")
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(count <= TosTokenOffchainMetadata.MAXIMUM_BYTES - out.size())
                    out.write(buffer, 0, count)
                }
            }
            return TosTokenOffchainMetadata.decode(out.toByteArray())
        }
    }
}
