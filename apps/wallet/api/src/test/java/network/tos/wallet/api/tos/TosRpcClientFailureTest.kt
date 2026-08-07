package network.tos.wallet.api.tos

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class TosRpcClientFailureTest {

    private lateinit var server: MockWebServer

    @Before
    fun startServer() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun stopServer() = server.close()

    @Test
    fun `malformed response fails closed`() {
        enqueue("not-json")
        assertThrows(Exception::class.java) { client().callRaw("test") }
    }

    @Test
    fun `TVM and JSON-RPC errors preserve node codes`() {
        enqueue("""{"ok":false,"code":422,"error":"bad cursor"}""")
        assertEquals(422, assertThrows(TosRpcException::class.java) { client().callRaw("test") }.code)

        enqueue("""{"error":{"code":-32001,"message":"node busy"}}""")
        assertEquals(-32001, assertThrows(TosRpcException::class.java) { client().callRaw("test") }.code)
    }

    @Test
    fun `timeout fails and next request reconnects`() {
        enqueue("""{"result":{}}""", delayMillis = 300)
        assertThrows(Exception::class.java) { client(timeoutMillis = 50).callObject("test") }

        enqueue("""{"result":{"seqno":7}}""")
        assertEquals(7, client().callObject("test").getInt("seqno"))
    }

    @Test
    fun `endpoint provider is evaluated for every request`() {
        val endpoint = java.util.concurrent.atomic.AtomicReference("http://127.0.0.1:1")
        val rpc = TosRpcClient(OkHttpClient(), { endpoint.get() })
        assertThrows(Exception::class.java) { rpc.callObject("test", JSONObject()) }
        endpoint.set(baseUrl())
        enqueue("""{"result":{"ready":true}}""")
        assertEquals(true, rpc.callObject("test").getBoolean("ready"))
    }

    private fun client(timeoutMillis: Long = 2_000) = TosRpcClient(
        OkHttpClient.Builder()
            .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .build(),
        { baseUrl() },
    )

    private fun baseUrl() = server.url("/").toString().trimEnd('/')

    private fun enqueue(body: String, delayMillis: Long = 0) {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(body)
                .apply {
                    if (delayMillis > 0) bodyDelay(delayMillis, TimeUnit.MILLISECONDS)
                }
                .build()
        )
    }
}
