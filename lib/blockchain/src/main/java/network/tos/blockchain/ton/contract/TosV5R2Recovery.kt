package network.tos.blockchain.ton.contract

import java.math.BigInteger
import network.tos.blockchain.ton.extensions.storeAddress
import org.ton.block.AddrStd
import org.ton.cell.Cell
import org.ton.cell.buildCell

class V5R2RecoveryBinding(val globalId: Int, network: ByteArray, val wallet: AddrStd, val module: AddrStd, val validUntil: Long) {
    private val networkBytes = network.copyOf()
    val network: ByteArray get() = networkBytes.copyOf()
    internal fun validate(time: Long) {
        require(networkBytes.size == 32) { "Network must be 32 bytes" }
        require(wallet.workchainId == 0 && module.workchainId == 0 && wallet.anycast.value == null && module.anycast.value == null) { "Basechain standard addresses required" }
        require(wallet != module) { "Wallet and module must differ" }
        require(time in 0..0xffffffffL && validUntil in 0..0xffffffffL && validUntil - time in 1..3600) { "Recovery TTL must be 1..3600" }
    }
}

/** POP proves possession only after fresh challenge and authenticated funded receipt verification. */
class TosV5R2Pop(binding: V5R2RecoveryBinding, val role: V5R2AuthRole, policy: V5R2Policy,
                   primaryKeyChainHash: ByteArray, rescueKey: ByteArray, challenge: ByteArray, provenTime: Long) {
    val request: Cell
    private val digestBytes: ByteArray
    val digest: ByteArray get() = digestBytes.copyOf()
    val signingContext = "TOS-RESCUE-POP-v1"
    init {
        binding.validate(provenTime)
        require(primaryKeyChainHash.size == 32 && rescueKey.size == 32) { "Invalid POP key binding" }
        require(challenge.size == 32 && challenge.any { it != 0.toByte() }) { "Nonzero 32-byte POP challenge required" }
        val parties = buildCell { storeAddress(binding.wallet); storeBytes(binding.module.address.toByteArray()) }
        val keys = buildCell { storeUInt(1, 8); storeBytes(primaryKeyChainHash); storeBytes(rescueKey); storeUInt(policy.id, 8) }
        request = buildCell {
            storeUInt(0x504f5033, 32); storeInt(binding.globalId, 32); storeBytes(binding.network); storeUInt(role.id, 8)
            storeBytes(challenge); storeUInt(binding.validUntil, 32); storeRef(parties); storeRef(keys)
        }
        digestBytes = buildCell { storeBytes("TOS-POP1".toByteArray(Charsets.US_ASCII)); storeRef(request) }.hash().toByteArray()
    }
    fun submission(signature: ByteArray): Cell {
        require(signature.size == role.signatureSize) { "Wrong POP signature length" }
        return buildCell { storeUInt(0x50505333, 32); storeRef(request); storeRef(TosPqWallet.byteChain(signature)) }
    }
}

/** SLH-only deployment framing. Pairing, live fee bounds and actual deployment are separate checks. */
class TosV5R2Preparation(binding: V5R2RecoveryBinding, moduleAmount: BigInteger, vaultAmount: BigInteger,
                         moduleInit: Cell, metadata: Cell, vaultInit: Cell, provenTime: Long) {
    val request: Cell
    val deploymentValue: BigInteger
    val digest: ByteArray get() = request.hash().toByteArray()
    val signingContext = "TOS-RESCUE-FEE-PREP-v1"
    init {
        binding.validate(provenTime)
        require(moduleAmount.signum() > 0 && vaultAmount.signum() > 0) { "Positive deployment amounts required" }
        require(moduleAmount.bitLength() <= 120 && vaultAmount.bitLength() <= 120) { "Deployment Coins overflow" }
        deploymentValue = moduleAmount.add(vaultAmount)
        val targets = buildCell {
            val a = (moduleAmount.bitLength() + 7) / 8; val b = (vaultAmount.bitLength() + 7) / 8
            storeUInt(a, 4); storeUInt(moduleAmount, a * 8); storeUInt(b, 4); storeUInt(vaultAmount, b * 8)
            storeRef(moduleInit); storeRef(metadata); storeRef(vaultInit)
        }
        request = buildCell {
            storeUInt(0x50525033, 32); storeInt(binding.globalId, 32); storeBytes(binding.network)
            storeAddress(binding.wallet); storeBytes(binding.module.address.toByteArray()); storeUInt(binding.validUntil, 32); storeRef(targets)
        }
    }
    fun submission(signature: ByteArray): Cell {
        require(signature.size == 7856) { "SLH preparation signature required" }
        return buildCell { storeUInt(0x46505233, 32); storeRef(request); storeRef(TosPqWallet.byteChain(signature)) }
    }
}
