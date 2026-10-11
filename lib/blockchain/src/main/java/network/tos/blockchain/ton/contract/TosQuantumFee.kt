package network.tos.blockchain.ton.contract

import java.math.BigInteger
import java.nio.ByteBuffer
import network.tos.blockchain.ton.extensions.storeAddress
import org.ton.block.AddrStd
import org.ton.cell.Cell
import org.ton.cell.CellType
import org.ton.cell.buildCell

enum class QuantumFeeClass(val id: Int, val submissionTag: Long, val requestTag: Long, val requestBits: Int, val requestRefs: Int) {
    RESCUE_AUTH(1,0x53554233,0x41553252,1019,1), POP(2,0x50505333,0x504f5033,616,2), PREPARE(3,0x46505233,0x50525033,875,1)
}
/** Fee framing only. Reserve this exact digest durably, verify/cache signatures, then recheck live proofs before broadcast. */
class TosQuantumFee(vault: AddrStd, configHash: ByteArray, epoch0: Long, val leaf: Int,
                 validUntil: Long, value: BigInteger, kind: QuantumFeeClass, payload: Cell, provenTime: Long) {
    val intent: Cell
    val digest: ByteArray get() = intent.hash().toByteArray()
    init {
        validatePayload(kind, payload)
        require(vault.workchainId == 0 && vault.anycast.value == null && configHash.size == 32) { "Invalid fee identity" }
        require(epoch0 in 0..0xffffffffL && provenTime in 0..0xffffffffL && validUntil in 0..0xffffffffL && validUntil - provenTime in 1..3600) { "Invalid fee time or TTL" }
        require(leaf in 0 until (1 shl 20)) { "Fee tree exhausted or invalid leaf" }
        require(provenTime >= epoch0) { "Before fee epoch" }
        require(leaf / 4L == (provenTime - epoch0) / 3600) { "New fee signature requires current slot" }
        require(value.signum() > 0 && value.bitLength() <= 120) { "Positive canonical fee Coins required" }
        val n = (value.bitLength() + 7) / 8
        intent = buildCell {
            storeUInt(0x46454534,32); storeBytes("TOS-RESCUE-FEE-v1".toByteArray(Charsets.US_ASCII)); storeUInt(kind.id,8)
            storeAddress(vault); storeBytes(configHash); storeUInt(leaf,32); storeUInt(validUntil,32)
            storeUInt(n,4); storeUInt(value,n*8); storeRef(payload)
        }
    }
    fun external(signature: ByteArray): Cell {
        require(signature.size == 2832) { "Fee signature length" }
        val s = ByteBuffer.wrap(signature)
        require(s.getInt(0) == 0 && s.getInt(4) == leaf && s.getInt(8) == 3 && s.getInt(2188) == 8) { "Fee signature profile or leaf mismatch" }
        return buildCell { storeRef(intent); storeRef(TosPqWallet.byteChain(signature)) }
    }
    companion object {
        fun validatePayload(kind: QuantumFeeClass, payload: Cell) {
            require(payload.type == CellType.ORDINARY && payload.levelMask.level == 0) { "Ordinary fee payload required" }
            val s = payload.beginParse()
            require(s.remainingBits == 32 && s.refs.size - s.refsPosition == 2) { "Fee payload shape" }
            require(s.loadUInt(32).toLong() == kind.submissionTag) { "Fee class and submission mismatch" }
            val request = s.loadRef()
            require(request.type == CellType.ORDINARY && request.levelMask.level == 0) { "Ordinary fee request required" }
            val r = request.beginParse()
            require(r.remainingBits == kind.requestBits && r.refs.size - r.refsPosition == kind.requestRefs) { "Fee request shape" }
            require(r.loadUInt(32).toLong() == kind.requestTag) { "Fee request constructor" }
            if (kind == QuantumFeeClass.RESCUE_AUTH) {
                r.loadBits(811)
                require(r.loadUInt(8).toInt() == 2) { "Fee vault cannot fund PRIMARY AUTH" }
            }
        }
    }
}
