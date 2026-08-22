package network.tos.wallet.api.tos

import network.tos.blockchain.ton.extensions.base64
import network.tos.blockchain.ton.extensions.cellFromBase64
import network.tos.blockchain.ton.extensions.loadAddress
import network.tos.blockchain.ton.extensions.toAccountId
import org.json.JSONArray
import org.ton.block.AddrStd
import org.ton.cell.Cell
import org.ton.cell.CellBuilder
import okio.ByteString.Companion.decodeBase64
import java.math.BigInteger
import java.util.Locale
import kotlin.math.abs

data class TosDnsEvidence(
    val canonicalName: String,
    val address: String,
    val checkpoint: TosBlockId,
    val resolverPath: List<String>,
    val renewalDeadline: Long,
)

/** TIP-1 fail-closed wallet resolver. All reads are bound to one MC checkpoint. */
class TosDnsResolver(private val source: TosSource) {
    fun resolveWallet(input: String, testnet: Boolean = false, now: Long = System.currentTimeMillis() / 1000): TosDnsEvidence {
        val name = canonicalName(input)
        var remaining = encodeName(name)
        val consensus = source.getConsensusBlock(testnet)
        require(consensus.seqno > 0 && consensus.blockUtime > 0) { "invalid DNS checkpoint" }
        require(abs(now - consensus.blockUtime) <= MAX_CHECKPOINT_AGE_SECONDS) { "stale DNS checkpoint" }
        var current = configRoot(source.getConfigParam(4, consensus.seqno, testnet))
        val path = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        var checkpoint: TosBlockId? = null
        var record: Cell? = null

        while (path.size < MAX_RESOLVER_CONTACTS && record == null) {
            require(seen.add(current)) { "DNS resolver cycle" }
            path += current
            val nameCell = CellBuilder.createCell { storeBytes(remaining) }.base64()
            val result = getter(
                current, "dnsresolve",
                listOf(TosSource.stackSlice(nameCell), stackNum(WALLET_CATEGORY)),
                consensus.seqno, testnet,
            )
            checkpoint = bindCheckpoint(checkpoint, result.blockId, consensus.seqno)

            // HTTP JSON-RPC serializes TVM top first: (int, cell) => [cell, int].
            val value = stackCell(result.stack, 0) ?: error("DNS record not found")
            val consumed = TosSource.stackReadBigInteger(result.stack, 1)?.longValueExact()
                ?: error("invalid DNS consumed-bit count")
            require(consumed > 0 && consumed % 8 == 0L && consumed <= remaining.size * 8L) {
                "invalid DNS consumed-bit boundary"
            }
            val bytes = (consumed / 8).toInt()
            require(isComponentBoundary(bytes, remaining)) {
                "DNS resolver stopped inside a component"
            }
            if (bytes == remaining.size) {
                record = value
                break
            }
            current = parseRecordAddress(value, NEXT_RESOLVER_TAG, allowCapability = false)
            remaining = remaining.copyOfRange(bytes, remaining.size)
        }
        val finalRecord = record ?: error("DNS resolver hop limit exhausted")
        val resolved = parseRecordAddress(finalRecord, SMC_ADDRESS_TAG, allowCapability = true)
        require(path.size >= 3) { "DNS path lacks canonical Domain Item" }
        val label = name.substringBeforeLast('.').substringAfterLast('.')
        val deadline = verifyItem(path[2], path[1], label, checkpoint!!, consensus.seqno, testnet, now)
        return TosDnsEvidence(name, resolved, checkpoint!!, path, deadline)
    }

    private fun getter(address: String, method: String, stack: List<JSONArray>, seqno: Int, testnet: Boolean): TosRunResult {
        val result = source.runGetMethod(address, method, stack, testnet, seqno)
        require(result.type == "smc.runResult" && result.exitCode == 0) { "$method failed" }
        require(result.blockId != null && result.blockId.seqno == seqno) { "$method changed DNS checkpoint" }
        return result
    }

    private fun verifyItem(
        item: String, collection: String, label: String, checkpoint: TosBlockId,
        seqno: Int, testnet: Boolean, now: Long,
    ): Long {
        val identity = getter(item, "get_nft_data", emptyList(), seqno, testnet)
        bindCheckpoint(checkpoint, identity.blockId, seqno)
        require(identity.stack.length() == 5) { "invalid Domain Item identity" }
        // (init,index,collection,owner,content) => [content,owner,collection,index,init].
        val index = TosSource.stackReadBigInteger(identity.stack, 3) ?: error("missing Domain Item index")
        val actualCollection = stackAddress(identity.stack, 2)
        require(actualCollection == collection) { "Domain Item belongs to another Collection" }
        val labelCell = CellBuilder.createCell { storeBytes(label.encodeToByteArray()) }
        val expectedIndex = BigInteger(1, labelCell.hash().toByteArray())
        require(index == expectedIndex) { "Domain Item index differs from label slice hash" }

        val mapped = getter(
            collection, "get_nft_address_by_index",
            listOf(stackNum(index)), seqno, testnet,
        )
        bindCheckpoint(checkpoint, mapped.blockId, seqno)
        require(stackAddress(mapped.stack, 0) == item) { "Collection maps label to another Domain Item" }

        val auction = getter(item, "get_auction_info", emptyList(), seqno, testnet)
        bindCheckpoint(checkpoint, auction.blockId, seqno)
        require(auction.stack.length() == 3) { "invalid DNS auction state" }
        // (bidder, amount, end) => [end, amount, bidder]. Any non-zero value is unusable,
        // including an ended but not finalized auction.
        val auctionEnd = TosSource.stackReadBigInteger(auction.stack, 0)?.longValueExact()
            ?: error("invalid DNS auction end")
        require(auctionEnd == 0L) { "DNS name is in an auction or awaits finalization" }

        val fill = getter(item, "get_last_fill_up_time", emptyList(), seqno, testnet)
        bindCheckpoint(checkpoint, fill.blockId, seqno)
        require(fill.stack.length() == 1) { "invalid DNS renewal clock" }
        val lastFill = TosSource.stackReadBigInteger(fill.stack, 0)?.longValueExact()
            ?: error("invalid DNS renewal clock")
        require(lastFill > 0 && lastFill <= Long.MAX_VALUE - LEASE_SECONDS) { "invalid DNS renewal clock" }
        val deadline = lastFill + LEASE_SECONDS
        require(isLeaseUsable(lastFill, now)) { "DNS lease expired" }
        return deadline
    }

    private fun bindCheckpoint(expected: TosBlockId?, actual: TosBlockId?, seqno: Int): TosBlockId {
        require(actual != null && actual.type == "tos.blockIdExt" && actual.seqno == seqno && actual.workchain == -1 &&
            hashLength(actual.rootHash) == 32 && hashLength(actual.fileHash) == 32) { "invalid DNS getter checkpoint" }
        if (expected != null) require(expected == actual) { "DNS getter checkpoint changed" }
        return actual
    }

    private fun hashLength(value: String): Int = try {
        value.decodeBase64()?.size ?: -1
    } catch (_: Throwable) {
        -1
    }

    private fun configRoot(boc: String): String {
        val slice = boc.cellFromBase64().beginParse()
        require(slice.bits.size - slice.bitsPosition == 256 && slice.refs.isEmpty()) { "invalid ConfigParam 4 cell" }
        return "-1:" + slice.loadBits(256).toByteArray().joinToString("") { "%02x".format(it) }
    }

    private fun stackCell(stack: JSONArray, index: Int): Cell? =
        TosSource.stackReadCellBytes(stack, index)?.cellFromBase64()

    private fun stackAddress(stack: JSONArray, index: Int): String =
        stackCell(stack, index)?.beginParse()?.loadAddress().let { it as? AddrStd }?.toAccountId()
            ?: error("invalid DNS address")

    private fun parseRecordAddress(cell: Cell, tag: Int, allowCapability: Boolean): String {
        val slice = cell.beginParse()
        require(slice.loadUInt(16).toInt() == tag) { "DNS category record type mismatch" }
        val address = (slice.loadAddress() as? AddrStd)?.toAccountId() ?: error("invalid DNS record address")
        if (allowCapability) {
            val flags = slice.loadUInt(8).toInt()
            require(flags == 0 || flags == 1) { "invalid DNS smart-contract flags" }
            if (flags == 1) slice.loadRef()
        }
        require(slice.bitsPosition == slice.bits.size && slice.refsPosition == slice.refs.size) {
            "trailing DNS record data"
        }
        return address
    }

    companion object {
        const val MAX_RESOLVER_CONTACTS = 8
        const val MAX_CHECKPOINT_AGE_SECONDS = 120L
        const val LEASE_SECONDS = 31_622_400L
        private const val NEXT_RESOLVER_TAG = 0xba93
        private const val SMC_ADDRESS_TAG = 0x9fd3
        internal val WALLET_CATEGORY = BigInteger(
            "105311596331855300602201538317979276640056460191511695660591596829410056223515"
        )

        fun canonicalName(input: String): String {
            require(input == input.trim() && !input.endsWith('.')) { "invalid DNS name" }
            val lowered = input.lowercase(Locale.ROOT)
            val labels = lowered.split('.')
            require(labels.size >= 2 && labels.last() == "tos" && labels.none { it.isEmpty() }) { "not a .tos name" }
            require(lowered.all { it.code in 0x21..0x7e }) { "DNS name must be ASCII" }
            require(labels.all { it.length <= 126 } && encodeName(lowered).size <= 127) { "DNS name is too long" }
            return lowered
        }

        fun encodeName(name: String): ByteArray = name.split('.').asReversed()
            .flatMap { it.encodeToByteArray().asIterable() + 0.toByte() }.toByteArray()

        fun isLeaseUsable(lastFill: Long, now: Long): Boolean =
            lastFill > 0 && lastFill <= Long.MAX_VALUE - LEASE_SECONDS && now <= lastFill + LEASE_SECONDS

        fun isComponentBoundary(consumedBytes: Int, query: ByteArray): Boolean =
            consumedBytes == query.size || consumedBytes > 0 && consumedBytes < query.size &&
                (query[consumedBytes - 1] == 0.toByte() || query[consumedBytes] == 0.toByte())

        private fun stackNum(value: BigInteger): JSONArray = JSONArray().put("num").put(value.toString())
    }
}
