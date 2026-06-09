package com.tonapps.wallet.api.tos

import com.tonapps.blockchain.ton.extensions.base64
import com.tonapps.blockchain.ton.extensions.cellFromBase64
import com.tonapps.blockchain.ton.extensions.loadAddress
import com.tonapps.blockchain.ton.extensions.storeAddress
import com.tonapps.blockchain.ton.extensions.toAccountId
import com.tonapps.blockchain.ton.extensions.toRawAddress
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.ton.block.AddrStd
import org.ton.cell.CellBuilder
import java.math.BigInteger

/**
 * TosSource — high-level entry point for the data layer to reach the TOS node JSON-RPC.
 *
 * Design: each method documents the tonapi method it replaces.
 * - Methods a bare node can serve are implemented with real RPC calls
 *   (balance / seqno / send / fee estimate / runGetMethod / raw history).
 * - Methods that need an indexer (jetton list / NFT list / fiat rates / rich parsed
 *   events) are not available from a bare TOS node, so they degrade explicitly
 *   (return empty + TODO) until a TOS indexer or client-side indexing is wired in.
 *
 * Usage: API.tos.getAccountState(addr). The base url comes from Constants;
 * point Constants.TOS_API_* at http://127.0.0.1:<json-rpc-port> to use a local node.
 */
class TosSource(
    httpClient: OkHttpClient,
    baseUrlProvider: (Boolean) -> String,
    apiKeyProvider: () -> String? = { null },
) {

    val rpc = TosRpcClient(httpClient, baseUrlProvider, apiKeyProvider)

    // ---------------------------------------------------------------------
    // Chain state
    // ---------------------------------------------------------------------

    /** getMasterchainInfo — replaces LiteServerApi.getMasterchainInfo / health check. */
    fun getMasterchainInfo(testnet: Boolean = false): TosMasterchainInfo {
        return TosMasterchainInfo.fromJson(rpc.callObject("getMasterchainInfo", testnet = testnet))
    }

    /** Server time — replaces API.getServerTime / LiteServerApi.getRawTime.
     *  The bare JSON-RPC has no standalone getTime, so approximate with the
     *  masterchain sync_utime (TODO: use a dedicated getTime if the node exposes one). */
    fun getServerTime(testnet: Boolean = false): Int {
        val info = rpc.callObject("getAddressInformation", JSONObject().put("address", ZERO_ADDRESS), testnet)
        val utime = info.optLong("sync_utime")
        return if (utime > 0) utime.toInt() else (System.currentTimeMillis() / 1000).toInt()
    }

    // ---------------------------------------------------------------------
    // Current account state (available from a bare node)
    // ---------------------------------------------------------------------

    /** getAddressInformation — replaces AccountsApi.getAccount (balance / state / code / data). */
    fun getAccountState(address: String, testnet: Boolean = false): TosAccountState {
        val params = JSONObject().put("address", address)
        return TosAccountState.fromJson(rpc.callObject("getAddressInformation", params, testnet))
    }

    /** getWalletInformation — replaces WalletApi.getAccountSeqno + part of getAccountPublicKey. */
    fun getWalletInformation(address: String, testnet: Boolean = false): TosWalletInfo {
        val params = JSONObject().put("address", address)
        return TosWalletInfo.fromJson(rpc.callObject("getWalletInformation", params, testnet))
    }

    /** seqno — replaces WalletApi.getAccountSeqno().seqno. Prefer walletInformation,
     *  fall back to runGetMethod("seqno") on failure. */
    fun getSeqno(address: String, testnet: Boolean = false): Int {
        return try {
            getWalletInformation(address, testnet).seqno
        } catch (e: Throwable) {
            try {
                val result = runGetMethod(address, "seqno", emptyList(), testnet)
                stackReadNumber(result.stack, 0)?.toInt() ?: 0
            } catch (e2: Throwable) {
                0
            }
        }
    }

    // ---------------------------------------------------------------------
    // Read-only contract calls (available from a bare node)
    // ---------------------------------------------------------------------

    /** runGetMethod — replaces BlockchainApi.execGetMethodForBlockchainAccount.
     *  Stack entries are [type, value]: num/cell/slice (see stack* helpers). */
    fun runGetMethod(
        address: String,
        method: String,
        stack: List<JSONArray> = emptyList(),
        testnet: Boolean = false,
    ): TosRunResult {
        val stackJson = JSONArray().apply { stack.forEach { put(it) } }
        val params = JSONObject()
            .put("address", address)
            .put("method", method)
            .put("stack", stackJson)
        return TosRunResult.fromJson(rpc.callObject("runGetMethod", params, testnet))
    }

    // ---------------------------------------------------------------------
    // Send / fee estimate (available from a bare node)
    // ---------------------------------------------------------------------

    /** sendBocReturnHash — replaces BlockchainApi.sendBlockchainMessage / LiteServerApi.sendRawMessage. */
    fun sendBoc(bocBase64: String, testnet: Boolean = false): TosSendResult {
        val params = JSONObject().put("boc", bocBase64)
        return TosSendResult.fromJson(rpc.callObject("sendBocReturnHash", params, testnet))
    }

    /** estimateFee — replaces the fee part of EmulationApi (full event-level emulate
     *  needs indexing/parsing; see the TODO below). */
    fun estimateFee(
        address: String,
        bodyBoc: String,
        initCodeBoc: String? = null,
        initDataBoc: String? = null,
        testnet: Boolean = false,
    ): TosFees {
        val params = JSONObject()
            .put("address", address)
            .put("body", bodyBoc)
        initCodeBoc?.let { params.put("init_code", it) }
        initDataBoc?.let { params.put("init_data", it) }
        return TosFees.fromJson(rpc.callObject("estimateFee", params, testnet))
    }

    // ---------------------------------------------------------------------
    // Raw transaction history (available from a bare node, but raw —
    // parsed into AccountEvent by TosEventMapper)
    // ---------------------------------------------------------------------

    /** getTransactions — partially replaces AccountsApi.getAccountEvents
     *  (returns raw transactions, parsed client-side). */
    fun getTransactions(
        address: String,
        limit: Int = 20,
        beforeLt: Long? = null,
        hash: String? = null,
        testnet: Boolean = false,
    ): List<TosRawTransaction> {
        val params = JSONObject()
            .put("address", address)
            .put("limit", limit)
        beforeLt?.let { params.put("lt", it.toString()) }
        hash?.let { params.put("hash", it) }
        val array = rpc.callArray("getTransactions", params, testnet)
        return (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let { TosRawTransaction.fromJson(it) }
        }
    }

    /** getTokenData — partially replaces JettonsApi.getJettonInfo / NFTApi
     *  (returns raw jetton/nft content). */
    fun getTokenData(address: String, testnet: Boolean = false): JSONObject {
        return rpc.callObject("getTokenData", JSONObject().put("address", address), testnet)
    }

    // ---------------------------------------------------------------------
    // Aggregate queries served by the TOS in-node wc=0 wallet index
    // (getAccountJettons / getAccountNfts). See doc/tos-wc0-wallet-index.md.
    // ---------------------------------------------------------------------

    /** node getAccountJettons -> the owner's jetton-wallet addresses (raw wc:hex). */
    fun getAccountJettonWallets(owner: String, testnet: Boolean = false): List<String> {
        val result = rpc.callObject("getAccountJettons", JSONObject().put("address", owner), testnet)
        val arr = result.optJSONArray("jettons") ?: return emptyList()
        return (0 until arr.length()).mapNotNull {
            arr.optJSONObject(it)?.optString("jetton_wallet")?.takeIf { s -> s.isNotBlank() }
        }
    }

    /** node getAccountNfts -> the owner's NFT item addresses (raw wc:hex). */
    fun getAccountNftItems(owner: String, testnet: Boolean = false): List<String> {
        val result = rpc.callObject("getAccountNfts", JSONObject().put("address", owner), testnet)
        val arr = result.optJSONArray("nfts") ?: return emptyList()
        return (0 until arr.length()).mapNotNull {
            arr.optJSONObject(it)?.optString("nft_item")?.takeIf { s -> s.isNotBlank() }
        }
    }

    /** jetton-wallet get_wallet_data() -> (balance, jetton_master).
     *  TOS runGetMethod returns the stack top-first, so for the standard return
     *  (balance, owner, jetton, code) the layout is [code, jetton, owner, balance]. */
    fun getJettonWalletData(jettonWallet: String, testnet: Boolean = false): TosJettonWalletData? {
        val result = runGetMethod(jettonWallet, "get_wallet_data", emptyList(), testnet)
        if (!result.success) {
            return null
        }
        val n = result.stack.length()
        if (n < 4) {
            return null
        }
        val balance = stackReadBigInteger(result.stack, n - 1) ?: return null
        val master = stackReadCellBytes(result.stack, n - 3)?.let { parseAddressFromBoc(it) }
        return TosJettonWalletData(jettonWallet, balance, master)
    }

    /** nft-item get_nft_data() -> (index, collection, owner).
     *  Stack is top-first; the standard return (init, index, collection, owner, content)
     *  is laid out as [content, owner, collection, index, init]. */
    fun getNftItemData(nftItem: String, testnet: Boolean = false): TosNftItemData? {
        val result = runGetMethod(nftItem, "get_nft_data", emptyList(), testnet)
        if (!result.success) {
            return null
        }
        val n = result.stack.length()
        if (n < 4) {
            return null
        }
        val index = stackReadBigInteger(result.stack, n - 2) ?: java.math.BigInteger.ZERO
        val collection = stackReadCellBytes(result.stack, n - 3)?.let { parseAddressFromBoc(it) }
        val owner = stackReadCellBytes(result.stack, n - 4)?.let { parseAddressFromBoc(it) }
        return TosNftItemData(nftItem, index, collection, owner)
    }

    /** master.get_wallet_address(owner) — resolve the owner's jetton wallet address for a known master. */
    fun getJettonWalletAddress(owner: String, jettonMaster: String, testnet: Boolean = false): String? {
        val ownerBoc = addressToSliceBoc(owner) ?: return null
        val result = runGetMethod(jettonMaster, "get_wallet_address", listOf(stackSlice(ownerBoc)), testnet)
        if (!result.success) return null
        val boc = stackReadCellBytes(result.stack, 0) ?: return null
        return parseAddressFromBoc(boc)
    }

    /** TODO(price): fiat rates need an external price source / TOS price service. Not served by a bare node. Returns empty. */
    fun getRates(tokens: List<String>, currencies: List<String>): Map<String, Nothing> = emptyMap()

    companion object {
        /** wc=0 all-zero address, used to probe node sync_utime etc. */
        const val ZERO_ADDRESS = "0:0000000000000000000000000000000000000000000000000000000000000000"

        // ----- runGetMethod stack builders -----
        fun stackNum(value: Long): JSONArray = JSONArray().put("num").put(value.toString())
        fun stackCell(bocBase64: String): JSONArray =
            JSONArray().put("cell").put(JSONObject().put("bytes", bocBase64))
        fun stackSlice(bocBase64: String): JSONArray =
            JSONArray().put("slice").put(JSONObject().put("bytes", bocBase64))

        /** Read the index-th num from the result stack (accepts ["num","123"] / hex 0x..). */
        fun stackReadNumber(stack: JSONArray, index: Int): Long? {
            val entry = stack.optJSONArray(index) ?: return null
            val raw = entry.optString(1).ifBlank { return null }
            return if (raw.startsWith("0x") || raw.startsWith("0X")) {
                raw.substring(2).toLongOrNull(16)
            } else {
                raw.toLongOrNull()
            }
        }

        /** Read the index-th num as a BigInteger (jetton balances may exceed Long). */
        fun stackReadBigInteger(stack: JSONArray, index: Int): BigInteger? {
            val entry = stack.optJSONArray(index) ?: return null
            val raw = entry.optString(1).ifBlank { return null }
            return try {
                if (raw.startsWith("0x") || raw.startsWith("0X")) BigInteger(raw.substring(2), 16)
                else BigInteger(raw)
            } catch (e: Throwable) {
                null
            }
        }

        /** Read the index-th cell/slice as a base64 BOC. */
        fun stackReadCellBytes(stack: JSONArray, index: Int): String? {
            val entry = stack.optJSONArray(index) ?: return null
            val type = entry.optString(0)
            if (type != "cell" && type != "slice") return null
            return when (val v = entry.opt(1)) {
                is JSONObject -> v.optString("bytes").takeIf { it.isNotBlank() }
                is String -> v.takeIf { it.isNotBlank() }
                else -> null
            }
        }

        /** owner address -> a slice stored in a cell, base64 BOC (input to get_wallet_address). */
        fun addressToSliceBoc(address: String): String? = try {
            val addr = AddrStd(address.toRawAddress())
            CellBuilder.createCell { storeAddress(addr) }.base64()
        } catch (e: Throwable) {
            null
        }

        /** Parse an address (raw wc:hex) from a result cell base64 BOC. */
        fun parseAddressFromBoc(boc: String): String? = try {
            (boc.cellFromBase64().beginParse().loadAddress() as? AddrStd)?.toAccountId()
        } catch (e: Throwable) {
            null
        }
    }
}
