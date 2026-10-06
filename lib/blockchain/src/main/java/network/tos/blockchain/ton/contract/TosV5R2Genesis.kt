package network.tos.blockchain.ton.contract

import java.nio.ByteBuffer
import java.util.Collections
import java.util.IdentityHashMap
import network.tos.blockchain.ton.extensions.storeAddress
import org.ton.block.AddrStd
import org.ton.cell.Cell
import org.ton.cell.CellType
import org.ton.cell.buildCell

enum class V5R2Policy(val id: Int) { READY(1), REQUIRED(2) }
data class V5R2Codes(val wallet: Cell, val module: Cell, val vault: Cell)
/** Pins are supplied by an independently authenticated release, never an RPC assertion. */
data class V5R2CodePins(val wallet: ByteArray, val module: ByteArray, val vault: ByteArray)

/** Complete PQ-only construction DAG. Pairing is not deployment, POP or readiness. */
class TosV5R2Genesis(
    private val code: V5R2Codes, pins: V5R2CodePins, private val globalId: Int,
    network: ByteArray, walletId: Long, primaryKey: ByteArray, rescueKey: ByteArray,
    policy: V5R2Policy, feeTreeId: ByteArray, feePublicKey: ByteArray, private val epoch0: Long
) {
    private val networkBytes: ByteArray
    private val feeKey: Cell
    val moduleData: Cell
    val moduleInit: Cell
    val metadata: Cell
    val walletData: Cell
    val walletInit: Cell
    val vaultData: Cell
    val vaultInit: Cell
    val address: AddrStd
    val moduleAddress: AddrStd
    val vaultAddress: AddrStd
    private val configBytes: ByteArray
    val configHash: ByteArray get() = configBytes.copyOf()
    init {
        checkCode(code.wallet, pins.wallet); checkCode(code.module, pins.module); checkCode(code.vault, pins.vault)
        require(network.size == 32 && primaryKey.size == 1312 && rescueKey.size == 32 && feeTreeId.size == 32 && feePublicKey.size == 60) { "Invalid V5R2 key or namespace length" }
        require(walletId in 0..0xffffffffL && epoch0 in 0..0xffffffffL) { "Unsigned wallet ID and epoch required" }
        val keyHeader = ByteBuffer.wrap(feePublicKey)
        require(keyHeader.int == 1 && keyHeader.int == 8 && keyHeader.int == 3) { "Fixed HSS L1 H20/W4 required" }
        networkBytes = network.copyOf()
        moduleData = buildCell {
            storeUInt(1, 8); storeInt(globalId, 32); storeBytes(networkBytes); storeUInt(1, 8)
            storeRef(TosPqWallet.byteChain(primaryKey)); storeBytes(rescueKey); storeUInt(policy.id, 8)
        }
        moduleInit = TosPqWallet.stateInit(code.module, moduleData)
        moduleAddress = AddrStd(0, moduleInit.hash())
        feeKey = TosPqWallet.byteChain(feePublicKey)
        metadata = buildCell {
            storeUInt(1, 8); storeUInt(1, 8); storeBytes(feeTreeId); storeUInt(epoch0, 32)
            storeUInt(3600, 32); storeUInt(4, 16); storeRef(feeKey)
        }
        val auth = buildCell {
            storeUInt(4, 8); storeUInt(2, 2); storeUInt(0, 16); storeUInt(1, 64)
            storeUInt(0, 64); storeUInt(0, 64); storeRef(moduleInit); storeRef(metadata)
        }
        walletData = buildCell {
            storeBit(false); storeUInt(0, 32); storeUInt(walletId, 32)
            storeBytes(ByteArray(32)); storeBit(false); storeRef(auth)
        }
        walletInit = TosPqWallet.stateInit(code.wallet, walletData)
        address = AddrStd(0, walletInit.hash())
        val paired = pairedVault(address)
        vaultData = paired.first; configBytes = paired.second
        vaultInit = TosPqWallet.stateInit(code.vault, vaultData)
        vaultAddress = AddrStd(0, vaultInit.hash())
    }
    private fun pairedVault(wallet: AddrStd): Pair<Cell, ByteArray> {
        val config = buildCell {
            storeUInt(1, 8); storeInt(globalId, 32); storeBytes(networkBytes)
            storeAddress(wallet); storeAddress(moduleAddress); storeRef(metadata)
        }.hash().toByteArray()
        val prefix = buildCell {
            storeUInt(0x41553252, 32); storeInt(globalId, 32); storeBytes(networkBytes)
            storeAddress(wallet); storeBytes(moduleAddress.address.toByteArray()); storeUInt(2, 8)
        }
        val parties = buildCell { storeAddress(wallet); storeBytes(moduleAddress.address.toByteArray()) }.hash().toByteArray()
        return buildCell {
            storeUInt(3, 8); storeUInt(0, 32); storeBytes(config); storeUInt(epoch0, 32)
            storeAddress(moduleAddress); storeBytes(parties); storeRef(feeKey); storeRef(prefix)
        } to config
    }
    /** Witnesses target the existing wallet. Fresh keys, POP and live-state approval remain mandatory. */
    fun successorFor(wallet: AddrStd): Pair<Cell, ByteArray> {
        require(wallet.workchainId == 0 && wallet.anycast.value == null) { "Basechain standard address required" }
        require(wallet != moduleAddress) { "Wallet and module must differ" }
        val paired = pairedVault(wallet)
        return TosPqWallet.stateInit(code.vault, paired.first) to paired.second.copyOf()
    }
    companion object {
        private fun checkCode(code: Cell, pin: ByteArray) {
            require(pin.size == 32 && code.hash().toByteArray().contentEquals(pin)) { "V5R2 code pin mismatch" }
            val seen = Collections.newSetFromMap(IdentityHashMap<Cell, Boolean>())
            val pending = ArrayDeque<Cell>(); pending.add(code)
            while (pending.isNotEmpty()) {
                val current = pending.removeLast()
                if (!seen.add(current)) continue
                require(current.type == CellType.ORDINARY && current.levelMask.level == 0) { "Ordinary level-zero code required" }
                pending.addAll(current.refs)
            }
        }
    }
}
