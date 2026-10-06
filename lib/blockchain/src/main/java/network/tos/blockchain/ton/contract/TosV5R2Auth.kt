package network.tos.blockchain.ton.contract

import java.math.BigInteger
import network.tos.blockchain.ton.extensions.storeAddress
import org.ton.block.AddrStd
import org.ton.cell.Cell
import org.ton.cell.buildCell

enum class V5R2AuthRole(val id: Int, val signatureSize: Int, val context: String) {
    PRIMARY(1, 2420, "TOS-AUTH-V2-ML-DSA-44-v1"), RESCUE(2, 7856, "TOS-AUTH-SLH-DSA-SHA2-128S-v1")
}
sealed class V5R2AuthAction {
    data class Execute(val actions: Cell) : V5R2AuthAction()
    data class Configure(val replacement: Pair<Cell, Cell>? = null) : V5R2AuthAction()
    data object LockPrimary : V5R2AuthAction()
    data class Migrate(val moduleInit: Cell, val metadata: Cell, val vaultInit: Cell) : V5R2AuthAction()
}

/** Immutable PQ wire request. Trusted state, custody and delivery are separate checks. */
class TosV5R2Auth(
    globalId: Int, network: ByteArray, wallet: AddrStd, module: AddrStd,
    val role: V5R2AuthRole, epoch: ULong, nonce: ULong, validUntil: Long,
    action: V5R2AuthAction, provenTime: Long
) {
    val request: Cell
    private val digestBytes: ByteArray
    val digest: ByteArray get() = digestBytes.copyOf()
    init {
        require(network.size == 32) { "Network must be 32 bytes" }
        require(wallet.workchainId == 0 && module.workchainId == 0 && wallet.anycast.value == null && module.anycast.value == null) { "Basechain standard addresses required" }
        require(wallet != module) { "Wallet and module must differ" }
        require(provenTime in 0..0xffffffffL && validUntil in 0..0xffffffffL && validUntil - provenTime in 1..3600) { "AUTH TTL must be 1..3600" }
        val kind: Int
        val payload: Cell
        when (action) {
            is V5R2AuthAction.Execute -> {
                validateActions(action.actions); kind = 0
                payload = buildCell { storeUInt(0x45584543, 32); storeRef(action.actions) }
            }
            is V5R2AuthAction.Configure -> {
                kind = 1
                payload = buildCell {
                    storeUInt(0x434f4e46, 32); storeUInt(2, 2); storeBit(action.replacement != null)
                    action.replacement?.let { pair -> storeRef(buildCell { storeRef(pair.first); storeRef(pair.second) }) }
                }
            }
            V5R2AuthAction.LockPrimary -> {
                kind = 3; payload = buildCell { storeUInt(0x4c4f434b, 32); storeUInt(1, 8) }
            }
            is V5R2AuthAction.Migrate -> {
                kind = 4
                payload = buildCell { storeUInt(0x4d494752, 32); storeRef(action.moduleInit); storeRef(action.metadata); storeRef(action.vaultInit) }
            }
        }
        require(role != V5R2AuthRole.PRIMARY || kind == 0) { "PRIMARY may only execute" }
        request = buildCell {
            storeUInt(0x41553252, 32); storeInt(globalId, 32); storeBytes(network)
            storeAddress(wallet); storeBytes(module.address.toByteArray()); storeUInt(role.id, 8)
            storeUInt(BigInteger(epoch.toString()), 64); storeUInt(BigInteger(nonce.toString()), 64)
            storeUInt(validUntil, 32); storeUInt(kind, 8); storeRef(payload)
        }
        digestBytes = buildCell { storeBytes("TOS-AUTH".toByteArray(Charsets.US_ASCII)); storeRef(request) }.hash().toByteArray()
    }
    fun submission(signature: ByteArray): Cell {
        require(signature.size == role.signatureSize) { "Wrong PQ signature length" }
        return buildCell { storeUInt(0x53554233, 32); storeRef(request); storeRef(TosPqWallet.byteChain(signature)) }
    }
    companion object {
        fun validateActions(actions: Cell) {
            var current = actions
            var count = 0
            while (true) {
                val s = current.beginParse()
                if (s.remainingBits == 0) {
                    require(s.refs.size == s.refsPosition) { "Action tail must be empty" }; return
                }
                require(count < 255) { "At most 255 send actions" }
                require(s.remainingBits == 40 && s.refs.size - s.refsPosition == 2) { "Send action shape" }
                require(s.loadUInt(32).toLong() == 0x0ec3c86dL) { "Only send actions allowed" }
                val mode = s.loadUInt(8).toInt()
                require(mode and 2 != 0 && mode and 44 == 0 && mode and 192 != 192) { "Forbidden send mode" }
                current = s.loadRef(); count += 1
            }
        }
    }
}
