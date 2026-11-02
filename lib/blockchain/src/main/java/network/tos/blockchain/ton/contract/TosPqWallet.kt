package network.tos.blockchain.ton.contract

import network.tos.blockchain.ton.extensions.storeAddress
import network.tos.blockchain.ton.extensions.storeCoins
import org.ton.block.AddrStd
import org.ton.boc.BagOfCells
import org.ton.cell.Cell
import org.ton.cell.buildCell
import org.ton.crypto.hex
import java.nio.ByteBuffer

/** Exact immutable AUTH roots; a separate funded wallet transports each submission. */
class TosPqWallet(val algorithm: Int, publicKey: ByteArray, val network: Int, val workchain: Int = 0) {
    val minimumVm = when (algorithm) { 1 -> 16; 2 -> 16; else -> error("Unsupported PQ profile") }
    private val keyBytes = publicKey.copyOf()
    val publicKey: ByteArray get() = keyBytes.copyOf()
    init { require(workchain in -128..127); require(publicKey.size == if (algorithm == 1) 1312 else 897) }
    val moduleCode = BagOfCells(hex(if (algorithm == 1) MLDSA_CODE else FALCON_CODE)).first()
    // The module-only account runs the ordinary TOS V5 code; one copy keeps them equal.
    val walletCode: Cell = TosWalletV5R1Contract.CODE
    val moduleData = buildCell {
        storeInt(network, 32)
        if (algorithm == 2) storeUInt(1, 16)
        storeRef(byteChain(publicKey))
    }
    val moduleStateInit = stateInit(moduleCode, moduleData)
    val moduleAddress = AddrStd(workchain, moduleStateInit.hash())
    val walletData = buildCell {
        storeBit(false); storeUInt(0, 32); storeUInt(0, 32); storeBytes(ByteArray(32)); storeBit(false)
        storeRef(buildCell { storeUInt(2, 2); storeUInt(0, 64); storeUInt(0, 64); storeBytes(moduleAddress.address.toByteArray()) })
    }
    val walletStateInit = stateInit(walletCode, walletData)
    val address = AddrStd(workchain, walletStateInit.hash())
    fun transferPayload(destination: AddrStd, nanotomi: Long, comment: String = ""): Cell {
        require(destination.anycast.value == null && nanotomi > 0)
        val bytes = comment.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 4096) { "Comment exceeds supported size" }
        val body = if (bytes.isEmpty()) Cell.empty() else byteChain(ByteArray(4) + bytes)
        val message = buildCell {
            storeUInt(4, 4); storeUInt(0, 2); storeAddress(destination); storeCoins(nanotomi)
            storeBit(false); storeCoins(0L); storeCoins(0L); storeUInt(0, 64); storeUInt(0, 32)
            storeBit(false); storeBit(true); storeRef(body)
        }
        return buildCell {
            storeUInt(0x0ec3c86d, 32); storeUInt(3, 8); storeRef(Cell.empty()); storeRef(message)
        }
    }
    fun request(epoch: ULong, nonce: ULong, validUntil: Long, now: Long, payload: Cell): Cell {
        require(now >= 0 && validUntil in (now + 1)..minOf(now + 3600, 0xffffffffL)) { "Invalid AUTH expiry" }
        require(epoch != ULong.MAX_VALUE && nonce != ULong.MAX_VALUE) { "Exhausted AUTH counter" }
        return buildCell {
            storeInt(network, 32); storeAddress(address); storeUInt(java.math.BigInteger(epoch.toString()), 64); storeUInt(java.math.BigInteger(nonce.toString()), 64)
            storeUInt(validUntil, 32); storeUInt(0, 8); storeRef(payload)
        }
    }
    fun signingMessage(request: Cell): ByteArray {
        val digest = buildCell { storeBytes("TOS-AUTH".toByteArray(Charsets.US_ASCII)); storeRef(request) }.hash().toByteArray()
        return if (algorithm == 1) digest else
            "TOS-AUTH-FALCON512-PADDED-v1".toByteArray(Charsets.US_ASCII) + byteArrayOf(0) +
                ByteBuffer.allocate(4).putInt(workchain).array() + moduleAddress.address.toByteArray() + digest
    }
    fun submission(request: Cell, signature: ByteArray, queryId: ULong = 0u): Cell {
        require(signature.size == if (algorithm == 1) 2420 else 666)
        return buildCell {
            storeUInt(if (algorithm == 1) 0x4d4c4434L else 0x46414c31L, 32); storeUInt(java.math.BigInteger(queryId.toString()), 64)
            storeRef(buildCell { storeUInt(0x41555448, 32); storeRef(request); storeBit(false) })
            storeRef(byteChain(signature))
        }
    }
    /** Strict live-state binding; no key substitution, classical fallback or seqno-as-AUTH-nonce. */
    fun authCounters(code: Cell, data: Cell): Pair<ULong, ULong> {
        require(code.hash() == walletCode.hash()) { "Unexpected PQ wallet code" }
        val s = data.beginParse()
        require(!s.loadBit()); s.loadUInt(32)
        require(s.loadUInt(32).toLong() == 0L)
        require(s.loadBits(256).toByteArray().all { it == 0.toByte() })
        require(!s.loadBit()) { "Unexpected wallet extensions" }
        val auth = s.loadRef().beginParse()
        require(s.remainingBits == 0 && s.refsPosition == s.refs.size)
        require(auth.loadUInt(2).toInt() == 2) { "Strict PQ authorization required" }
        val epoch = auth.loadUInt(64).toString().toULong()
        val nonce = auth.loadUInt(64).toString().toULong()
        require(auth.loadBits(256).toByteArray().contentEquals(moduleAddress.address.toByteArray())) { "Wrong AUTH root" }
        require(auth.remainingBits == 0 && auth.refsPosition == auth.refs.size)
        return epoch to nonce
    }
    companion object {
        fun byteChain(bytes: ByteArray): Cell {
            require(bytes.isNotEmpty())
            var tail: Cell? = null
            for (part in bytes.asList().chunked(127).asReversed()) {
                val next = tail
                tail = buildCell { storeBytes(part.toByteArray()); if (next != null) storeRef(next) }
            }
            return requireNotNull(tail)
        }
        fun stateInit(code: Cell, data: Cell) = buildCell {
            storeBit(false); storeBit(false); storeBit(true); storeRef(code)
            storeBit(true); storeRef(data); storeBit(false)
        }
        private const val MLDSA_CODE = "b5ee9c7241020801000151000114ff00f4a413f4bcf2c80b0102012002030201480405000af230f2c76c01e6d001d0d70b0371b0925f03e020d749c120925f03e020d70b1f82104d4c4434bd925f03e0d31f31d33f31d4d4d1ed44d0d21fd4d1f8355220baf2e70923d0d31f01821041555448baf2e713d4f404d1206e91308e10d020d7498308ba01d74ac000b0f2e710e220d0d21f04baf2e70902fa4021060013a1273bda89a1a43fa9a301fe20d74981010bba21d74ac000b0f2e71120d70b02c004f2e711fa44f828fa445033ba5213bd12b0f2e71130d33f31d33f31d31f21f823bcf823810e10a013bb12b0f2e70dd30701c103f2e713d431d1028230544f532d41555448c8cb3fccc9f900c8cbffc98298544f532d415554482d4d4c2d4453412d34342d7631c8cba707005ac95a14f93100f2e710037003a112b60972fb0271708018c8cb055004cf1623fa0213cb6912cb00ccc98040fb00afa7c4cb"
        private const val FALCON_CODE = "b5ee9c724102080100016e000114ff00f4a413f4bcf2c80b0102012002030201480405000af230f2c76c01f6d001d0d70b0371b0925f03e020d749c120925f03e020d70b1f821046414c31bd925f03e0d31f31d33f31d4d4d1ed44d0d21fd30f01c001f2e713d4d1f8355220baf2e70923d0d31f01821041555448baf2e713d4f404d1206e91308e10d020d7498308ba01d74ac000b0f2e710e220d0d21f04baf2e70902fa4021060017a02c1fda89a1a43fa61fa9a301cc20d74981010bba21d74ac000b0f2e71120d70b02c004f2e711fa44f828fa445033ba5213bd12b0f2e71130d33f31d33f31d31f21f823bcf823810e10a013bb12b0f2e70dd30701c103f2e713d431d1f828fa44048230544f532d41555448c8cb3fccc9f900700700b282d0544f532d415554482d46414c434f4e3531322d5041444445442d7631c8cbdfcb0712ca1f14cbff13cbffc94033f93101f2e710037003a112b60972fb0271708018c8cb055004cf1623fa0213cb6912cb00ccc98040fb00b8d77e97"
    }
}
