package network.tos.wallet

import network.tos.blockchain.ton.extensions.base64
import org.json.JSONArray
import org.json.JSONObject
import org.ton.cell.buildCell
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.Collections

/** Bounded public-data transport fixture. No chain-proof or broadcast claim. */
internal class ClassicRecoveryRpcFixture(private val versions: Map<String, String>) : AutoCloseable {
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    val endpoint = "http://127.0.0.1:${server.localPort}"
    val observations: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
    private val thread = Thread {
        val deadline = System.currentTimeMillis() + 180_000
        var count = 0
        while (!server.isClosed && count < 256 && System.currentTimeMillis() < deadline) {
            val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
                catch (_: java.io.IOException) { break }
            socket.use { connection ->
                connection.soTimeout = 3_000
                try {
                    val reader = connection.getInputStream().bufferedReader(Charsets.UTF_8)
                    require(reader.readLine().startsWith("POST "))
                    var length = 0
                    var headerBytes = 0
                    while (true) {
                        val line = reader.readLine() ?: error("Missing header")
                        headerBytes += line.length
                        require(headerBytes <= 8192)
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                    }
                    require(length in 1..65_536)
                    val body = CharArray(length)
                    var received = 0
                    while (received < length) {
                        val read = reader.read(body, received, length - received)
                        require(read > 0)
                        received += read
                    }
                    val request = JSONObject(String(body))
                    val method = request.getString("method")
                    val params = request.optJSONObject("params") ?: JSONObject()
                    val address = params.optString("address")
                    observations.add(method to address)
                    val result = response(method, params)
                    val answer = JSONObject().put("jsonrpc", "2.0").put("id", request.opt("id"))
                    if (result != null) answer.put("result", result)
                    else answer.put("error", JSONObject().put("code", -32601).put("message", "Unsupported fixture method"))
                    val bytes = answer.toString().toByteArray(Charsets.UTF_8)
                    val output = connection.getOutputStream()
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    output.write(bytes)
                    output.flush()
                } catch (_: Exception) { /* Close malformed fixture requests without a success response. */ }
            }
            count++
        }
    }.apply { isDaemon = true; name = "classic-recovery-public-rpc" }

    init { server.soTimeout = 500; thread.start() }
    private fun response(method: String, params: JSONObject): Any? {
        val address = params.optString("address")
        val version = versions[address]
        return when (method) {
            "getMasterchainInfo" -> JSONObject().put("last", JSONObject().put("workchain", -1)
                .put("shard", "-9223372036854775808").put("seqno", 1).put("root_hash", "00".repeat(32)).put("file_hash", "11".repeat(32)))
            "getConfigParam" -> {
                val cell = when (params.getInt("param")) {
                    19 -> buildCell { storeInt(3, 32) }
                    8 -> buildCell { storeUInt(0xc4, 8); storeUInt(16, 32); storeUInt(0, 64) }
                    else -> return null
                }
                JSONObject().put("@type", "configInfo").put("config", JSONObject().put("@type", "tvm.cell").put("bytes", cell.base64()))
            }
            "getAddressInformation" -> JSONObject().put("balance", if (version == "v4R2") "1000000000" else "0")
                .put("state", when (version) { "v5R1" -> "active"; "v5Beta" -> "frozen"; else -> "uninit" })
                .put("code", "").put("data", "").put("sync_utime", System.currentTimeMillis() / 1000)
            "getAccountTokens", "getAccountNfts" -> {
                val tokens = method == "getAccountTokens"
                val rows = JSONArray()
                if (tokens && version == "v3R2") rows.put(JSONObject().put("token_wallet", "0:" + "22".repeat(32)))
                if (!tokens && version == "v3R1") rows.put(JSONObject().put("nft_item", "0:" + "33".repeat(32)))
                JSONObject().put("@type", if (tokens) "wallet.accountTokens" else "wallet.accountNfts")
                    .put(if (tokens) "tokens" else "nfts", rows)
                    .put("index_state", JSONObject().put("complete", version != "v4R1"))
            }
            "getTransactions" -> JSONArray()
            else -> null
        }
    }
    override fun close() {
        server.close()
        thread.join(4_000)
        check(!thread.isAlive) { "Recovery fixture did not stop" }
    }
}
