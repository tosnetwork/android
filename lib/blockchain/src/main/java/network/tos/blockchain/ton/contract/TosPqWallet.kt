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
    val walletCode = BagOfCells(hex(WALLET_CODE)).first()
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
        private const val WALLET_CODE = "b5ee9c72410225010007e2000114ff00f4a413f4bcf2c80b01020120020302014804050124f220d70b1f82107369676ebaf2e08a7f8ad81c0202cc0607020120121302b5d90e8698180b8d8492f81c7a690eba4e090492f81f010eb858f90410820aaaa245d4c9836097d201800f807701890410832bc3a375e90c10839b4b73a5ed8492f81f0410832bc3a375d718118906ba4c081505cc8987038456c714081c04f7bdda89a10083ae43ae17ffda89a1020283ae43e8086241ae9523a924da03c5a2a82647021c21b6784380031e9a63b67841a1ae160380071d0603b6792263c4ffda89a1a401a63f020241ae31e80860093443083f75e5ae24034803bc49a1ae160384032cd844e0da8267bc05919401963e039e2de8019993daa9c067090a0d0b019aed44d0810141d721f4043120d74a91d4926d01e2d16ef2e70f8020d72101d074d721fa4030fa44f828fa443058bd915be0ed44d0810141d721f4058307f40e6fa1319130e18040d721707fdb3c1e01f404206e9530705470008e14d0d301d33fd33fd3ffd123c20024c104b0f2e70ee223c300f2e7080620d74981010bba21d74ac000b0f2e71120d70b02c004f2e711fa44f828fa445033ba5213bd12b0f2e71126baf2e70804d31f01821041555448baf2e713d4f404d121d0d21ff83512baf2e709fa40f82812c7050c01e401206e9530705470008e14d0d301d33fd33fd3ffd123c20024c104b0f2e70ee25b02d0d301fa40d121c20022c104b0f2e70e02c20121c001b0f2d70e22843fbaf2d71202a4700220d74981010bba21d74ac000b0f2e71120d70b02c004f2e711fa44f828fa445033ba5213bd12b0f2e711120f01fcc000f2e713017f21d73930709421c700b38e2d01d72820761e436c20d749c008f2e09320d74ac002f2e09320d71d06c712c2005230b0f2d089d74cd7393001a4e86c128407bbf2e093d74ac000f2e09320d09420c700b38e21d72820761e436cd30721802cb0c300f2d713018100c0b08100c0baf2d713d430d0e830017f1102ecf2e70ad33f5114baf2e70bd33f5117baf2e70c26843fbaf2d712d31f21f823bcf823500ba012bb19b0f2e70d07d307d4d124c0038eb225db3c286ef2d71008d020d7498308ba21d74ac000b0f2e710028230544f532d41555448c8cb3fccc9f9004005f910f2e710973234066ef2e710e203a44303040d0e01f4208407b0807fb021ab0784efb022abf702c07f0184efbab0018100edbeb0f2d7102083f7baf2d7102082f0c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac037abaf2d710208306baf2d7102082f026e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05ba10001a03c8cb0112cb3fcb3fcbffc902001803c8cb0112cb3fcb3fcbffc900faf2d7102082f0ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7fbaf2d7102082f026e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc85baf2d71020c000f2d71082f0c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac03fabaf2d7100078ed44d0d200d31f810120d718f40430049a21841fbaf2d71201a401de24d0d70b01c201966c22706d4133de02c8ca00cb1f01cf16f400ccc9ed54ed5502012014150019be5f0f6a2684080a0eb90fa02c02012016170201481a1b006db7f5dda89a1020283ae43e8086241ae9523a924da03c5a240dd2a60e0a8e0011c29a1a603a67fa67fa7ffa247840049820961e5ce1dc5002015818190019adce76a2684020eb90eb85ffc00019af1df6a2684010eb90eb858fc00017b325fb51341c75c875c2c7e00011b262fb513435c2802001feeda2edfbed44d0810141d721f4043120d74a91d4926d01e2d1206e913099d0d70b01c201f2d70fe2218308d722028308d723208020d721d21ff83512baf2e094d31fd31fd31fed44d0d200d31f20d31fd3ffd70a000af90140ccf9109a28945f0adb31e1f2c087df02b35007b0f2d0845125baf2e0855036baf2e086f823bb1d014af2d08821841fbaf2d0852292f800de01a47fc8ca00cb1f01cf16c9ed542092f80fde70db3c1e03f6eda2edfb02f404216e91328e4d521321d73930709421c700b38e2d01d72820761e436c20d749c008f2e09320d74ac002f2e09320d71d06c712c2005230b0f2d089d74cd7393001a4e86c128407bbf2e093d74ac000f2e093ed55e201d20001c000925f03e020d70b07c005e30231ebd72c0814209170e30e5210b11f2021014c016eb312b1f2d71378d721d33ffa40d1ed44d0810141d721f4043120d74a91d4926d01e2d15922000c01d72c081c1201908e3930d72c08248e2d21f2e092d200ed44d0d2005113baf2d08f54503091319c01810140d721d70a00f2e08ee2c8ca0058cf16c9ed5493f2c08de2e30d20d74a935bdb31e1d74cd02401e202206e9530705470008e14d0d301d33fd33fd3ffd123c20024c104b0f2e70ee25b01c201f2d70f66baf2e70b20843fbaf2d7127101a4700320d74981010bba21d74ac000b0f2e71120d70b02c004f2e711fa44f828fa445033ba5213bd12b0f2e711413003c8cb0112cb3fcb3fcbffc970230074ed44d0d200d31f810120d718f40430049a21841fbaf2d71201a401de24d0d70b01c201966c22706d4133de02c8ca00cb1f01cf16f400ccc9ed54009801fa4001fa44f828fa443058baf2e091ed44d0810141d718f404059d7fc8ca0040338307f453f2e08b8e14128307f45bf2e08c21d70a00216e01b3b0f2d090e2c858cf16f40058cf16c9ed54c7bcc44c"
    }
}
