package network.tos.blockchain.ton.contract

import network.tos.blockchain.ton.extensions.loadAddress
import org.ton.block.AddrStd
import org.ton.boc.BagOfCells
import org.ton.cell.Cell

/** Strict QSP1 decoding only. A decoded request never establishes custody or chain authentication. */
class QuantumStoredSelfPayRequest private constructor(
    val cell: Cell, val globalId: Int, namespace: ByteArray, val wallet: AddrStd,
    authorityHash: ByteArray, val role: QuantumAuthRole, val epoch: ULong, val nonce: ULong,
    val deadline: Long, val kind: Int, val executeActions: Cell?,
) {
    private val namespaceBytes = namespace.copyOf()
    private val authorityBytes = authorityHash.copyOf()
    val namespace: ByteArray get() = namespaceBytes.copyOf()
    val authorityHash: ByteArray get() = authorityBytes.copyOf()
    fun requireIdentity(identity: TosQuantumSelfPayIdentity) {
        require(wallet == identity.address && globalId == identity.wallet.initialAuthority.globalId &&
            namespaceBytes.contentEquals(identity.wallet.initialAuthority.namespace)) { "Stored authorization identity mismatch" }
    }
    companion object {
        fun parse(bytes: ByteArray): QuantumStoredSelfPayRequest {
            TosQuantumSelfPayIdentity.preflightOrdinaryBoc(bytes, 1_048_576)
            return parse(BagOfCells(bytes).roots.single())
        }
        fun parse(cell: Cell): QuantumStoredSelfPayRequest {
            QuantumSelfPayAuthority.ordinary(cell)
            val s = cell.beginParse()
            // QSP1: 32+32+256+267+256+8+64+64+32+8 bits, one action-payload reference.
            require(s.remainingBits == 1019 && s.refs.size == 1) { "Stored request shape" }
            require(s.loadUInt(32).toLong() == 0x51535031L) { "Stored request magic" }
            val globalId = s.loadInt(32).toInt(); val namespace = s.loadBits(256).toByteArray()
            val wallet = s.loadAddress() as? AddrStd ?: error("Stored request needs a standard address")
            require(wallet.workchainId == 0 && wallet.anycast.value == null)
            val authority = s.loadBits(256).toByteArray()
            val roleId = s.loadUInt(8).toInt()
            val role = requireNotNull(QuantumAuthRole.entries.singleOrNull { it.id == roleId })
            val epoch = s.loadUInt(64).toString().toULong(); val nonce = s.loadUInt(64).toString().toULong()
            val deadline = s.loadUInt(32).toLong(); val kind = s.loadUInt(8).toInt()
            require(deadline > 0 && (role != QuantumAuthRole.PRIMARY || kind == 0))
            val payload = s.loadRef(); require(s.remainingBits == 0 && s.refsPosition == s.refs.size)
            QuantumSelfPayAuthority.ordinary(payload)
            val p = payload.beginParse(); var actions: Cell? = null
            when (kind) {
                0 -> {
                    require(p.remainingBits == 32 && p.refs.size == 1 && p.loadUInt(32).toLong() == 0x45584543L)
                    require(nonce != ULong.MAX_VALUE) { "Execute nonce exhausted" }
                    val list = p.loadRef(); TosQuantumAuth.validateActions(list); actions = list
                }
                1 -> {
                    require(role == QuantumAuthRole.RESCUE && epoch != ULong.MAX_VALUE)
                    require(p.remainingBits == 34 && p.refs.isEmpty() && p.loadUInt(32).toLong() == 0x434f4e46L && p.loadUInt(2).toInt() == 2)
                }
                3 -> {
                    require(role == QuantumAuthRole.RESCUE && epoch != ULong.MAX_VALUE)
                    require(p.remainingBits == 40 && p.refs.isEmpty() && p.loadUInt(32).toLong() == 0x4c4f434bL && p.loadUInt(8).toInt() == 1)
                }
                4 -> {
                    require(role == QuantumAuthRole.RESCUE && epoch != ULong.MAX_VALUE)
                    require(p.remainingBits == 32 && p.refs.size == 1 && p.loadUInt(32).toLong() == 0x4d494752L)
                    val successor = QuantumSelfPayAuthority.parse(p.loadRef())
                    require(successor.globalId == globalId && successor.namespace.contentEquals(namespace))
                }
                else -> error("Unsupported stored self-pay request kind")
            }
            require(p.remainingBits == 0 && p.refsPosition == p.refs.size) { "Stored payload tail" }
            return QuantumStoredSelfPayRequest(cell, globalId, namespace, wallet, authority, role, epoch, nonce, deadline, kind, actions)
        }
    }
}
