package network.tos.wallet.api.tos

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class TosTokenOffchainMetadataTest {
    private fun decode(text: String) = TosTokenOffchainMetadata.decode(text.toByteArray(Charsets.UTF_8))

    @Test fun issuerFieldsAndPrecisionAreBoundedWithoutFloatingPoint() {
        assertEquals(TosTokenOffchainMetadata("测试币", "CR", 6), decode("""{"name":"测试币","symbol":"CR","decimals":"6"}"""))
        assertEquals(9, decode("{}").decimals)
        assertEquals(0, decode("""{"decimals":0}""").decimals)
        assertEquals(255, decode("""{"decimals":"255"}""").decimals)
        for (value in listOf("true", "null", "6.0", "1e1", "-1", "256", "\"9x\"", "\"1000\"")) {
            assertThrows(Exception::class.java) { decode("{\"decimals\":$value}") }
        }
    }

    @Test fun malformedOversizedAndDeepJSONAreRejectedBeforeProjection() {
        for (value in listOf("[]", "{}{}", "{\"name\":7}", "{\"symbol\":\"${"x".repeat(33)}\"}",
            "{\"name\":\"${"测".repeat(43)}\"}", "{\"ignored\":" + "[".repeat(9) + "0" + "]".repeat(9) + "}")) {
            assertThrows(Exception::class.java) { decode(value) }
        }
        assertThrows(Exception::class.java) { TosTokenOffchainMetadata.decode(byteArrayOf(123, 34, -1, 34, 58, 48, 125)) }
        assertThrows(Exception::class.java) { TosTokenOffchainMetadata.decode(ByteArray(65_537)) }
    }

    @Test fun onlyHTTPSAndIPFSPointersWithoutCredentialsAreAccepted() {
        assertEquals("https://example.test/token.json", TosTokenOffchainMetadata.url("https://example.test/token.json").toString())
        assertEquals("https://ipfs.io/ipfs/bafyexample/token.json", TosTokenOffchainMetadata.url("ipfs://bafyexample/token.json").toString())
        for (uri in listOf("http://example.test/a", "file:///tmp/a", "javascript:alert(1)",
            "https://user:secret@example.test/a", "https://example.test/a#fragment", "https://example.test/" + "a".repeat(2048))) {
            assertThrows(Exception::class.java) { TosTokenOffchainMetadata.url(uri) }
        }
    }

    @Test fun isolatedTransportChecksStreamingSizeStatusAndRequestHeaders() {
        for (mode in listOf("valid", "redirect", "largeKnown", "largeUnknown")) {
            var requests = 0
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                requests++
                val request = chain.request()
                assertEquals("GET", request.method)
                assertNull(request.header("Authorization")); assertNull(request.header("X-API-Key")); assertNull(request.header("Cookie"))
                val bytes = if (mode.startsWith("large")) ByteArray(65_537) { 32 } else "{}".toByteArray()
                val body = if (mode == "largeUnknown") object : ResponseBody() {
                    override fun contentType() = "application/json".toMediaType()
                    override fun contentLength() = -1L
                    override fun source() = Buffer().write(bytes)
                } else bytes.toResponseBody("application/json".toMediaType())
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("fixture")
                    .code(if (mode == "redirect") 307 else 200).header("Location", "https://other.test/a").body(body).build()
            }.build()
            val client = TosTokenOffchainMetadataClient(http)
            if (mode == "valid") assertEquals(9, client.read("https://example.test/a").decimals)
            else assertThrows(Exception::class.java) { client.read("https://example.test/a") }
            assertEquals(1, requests)
        }
    }

    @Test fun cancellationDoesNotBecomeDefaultPrecision() {
        Thread.currentThread().interrupt()
        try { assertThrows(InterruptedException::class.java) { TosTokenOffchainMetadataClient().read("https://example.test/a") } }
        finally { Thread.interrupted() }
    }
}
