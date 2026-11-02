package network.tos.blockchain.ton.contract

import java.math.BigInteger
import network.tos.blockchain.ton.extensions.storeAddress
import org.ton.block.AddrStd
import org.ton.cell.Cell
import org.ton.cell.CellType
import org.ton.cell.buildCell

/** Self-pay v1 codecs. Decoding is not an authenticated account or policy proof. */
class QuantumSelfPayAuthority(
    val globalId: Int, namespace: ByteArray, primaryKey: ByteArray, rescueKey: ByteArray,
    val policy: QuantumPolicy = QuantumPolicy.READY
) {
    private val namespaceBytes = namespace.copyOf()
    private val primaryBytes = primaryKey.copyOf()
    private val rescueBytes = rescueKey.copyOf()
    val namespace: ByteArray get() = namespaceBytes.copyOf()
    val primaryKey: ByteArray get() = primaryBytes.copyOf()
    val rescueKey: ByteArray get() = rescueBytes.copyOf()
    init { require(namespace.size == 32 && primaryKey.size == 1312 && rescueKey.size == 32) }
    val cell: Cell = buildCell {
        storeUInt(1, 8); storeInt(globalId, 32); storeBytes(namespaceBytes); storeUInt(1, 8)
        storeRef(TosPqWallet.byteChain(primaryBytes)); storeBytes(rescueBytes); storeUInt(policy.id, 8)
    }
    val hash: ByteArray get() = cell.hash().toByteArray()

    companion object {
        fun parse(cell: Cell): QuantumSelfPayAuthority {
            ordinary(cell)
            val s = cell.beginParse()
            require(s.remainingBits == 568 && s.refs.size == 1) { "Self-pay authority shape" }
            require(s.loadUInt(8).toInt() == 1)
            val globalId = s.loadInt(32).toInt()
            val namespace = s.loadBits(256).toByteArray()
            require(s.loadUInt(8).toInt() == 1) { "Unsupported daily suite" }
            val primary = bytes(s.loadRef(), 1312)
            val rescue = s.loadBits(256).toByteArray()
            val policyId = s.loadUInt(8).toInt()
            val policy = QuantumPolicy.entries.single { it.id == policyId }
            return QuantumSelfPayAuthority(globalId, namespace, primary, rescue, policy)
        }

        internal fun ordinary(cell: Cell) {
            require(cell.type == CellType.ORDINARY && cell.levelMask.level == 0) { "Ordinary level-zero cell required" }
        }

        internal fun bytes(root: Cell, length: Int): ByteArray {
            val output = ByteArray(length)
            var current = root
            var offset = 0
            while (true) {
                ordinary(current)
                val s = current.beginParse()
                val remaining = length - offset
                val count = minOf(127, remaining)
                require(count > 0 && s.remainingBits == count * 8 && s.refs.size == if (remaining > 127) 1 else 0) { "Noncanonical byte chain" }
                s.loadBits(count * 8).toByteArray().copyInto(output, offset)
                offset += count
                if (offset == length) return output
                current = s.loadRef()
            }
        }
    }
}

class QuantumSelfPayState(
    val authority: QuantumSelfPayAuthority, val walletId: Long = 0, val seqno: Long = 0,
    val retired: Int = 0, val epoch: ULong = 0u, val primaryNonce: ULong = 0u,
    val rescueNonce: ULong = 0u
) {
    init { require(walletId in 0..0xffffffffL && seqno in 0..0xffffffffL && retired and 2.inv() == 0) }
    val cell: Cell = buildCell {
        storeUInt(1, 8); storeUInt(seqno, 32); storeUInt(walletId, 32); storeUInt(retired, 16)
        storeUInt(BigInteger(epoch.toString()), 64); storeUInt(BigInteger(primaryNonce.toString()), 64)
        storeUInt(BigInteger(rescueNonce.toString()), 64); storeRef(authority.cell)
    }
    companion object {
        fun parse(cell: Cell): QuantumSelfPayState {
            QuantumSelfPayAuthority.ordinary(cell)
            val s = cell.beginParse()
            require(s.remainingBits == 280 && s.refs.size == 1) { "Self-pay state shape" }
            require(s.loadUInt(8).toInt() == 1)
            val seqno = s.loadUInt(32).toLong()
            val walletId = s.loadUInt(32).toLong()
            val retired = s.loadUInt(16).toInt()
            val epoch = s.loadUInt(64).toString().toULong()
            val primary = s.loadUInt(64).toString().toULong()
            val rescue = s.loadUInt(64).toString().toULong()
            return QuantumSelfPayState(QuantumSelfPayAuthority.parse(s.loadRef()), walletId, seqno, retired, epoch, primary, rescue)
        }
    }
}

/** Birth identity must remain in backup after same-address key rotation. */
class TosQuantumSelfPayWallet(val initialAuthority: QuantumSelfPayAuthority, val walletId: Long = 0) {
    val code: Cell = TosQuantumSelfPayCode.wallet
    val initialData = QuantumSelfPayState(initialAuthority, walletId).cell
    val stateInit = TosPqWallet.stateInit(code, initialData)
    val address = AddrStd(0, stateInit.hash())

    fun readState(account: AddrStd, code: Cell, data: Cell): QuantumSelfPayState {
        require(account == address && code.hash() == this.code.hash()) { "Wallet identity mismatch" }
        val state = QuantumSelfPayState.parse(data)
        require(state.walletId == walletId && state.authority.globalId == initialAuthority.globalId &&
            state.authority.namespace.contentEquals(initialAuthority.namespace)) { "Wallet network or ID changed" }
        return state
    }

    fun external(body: Cell, deploy: Boolean): Cell = buildCell {
        storeUInt(8, 4); storeAddress(address); storeUInt(0, 4)
        storeBit(deploy)
        if (deploy) { storeBit(true); storeRef(stateInit) }
        storeBit(true); storeRef(body)
    }
}

sealed class QuantumSelfPayAction {
    data class Execute(val actions: Cell) : QuantumSelfPayAction()
    data object ConfigureRequired : QuantumSelfPayAction()
    data object LockPrimary : QuantumSelfPayAction()
    data class Migrate(val successor: QuantumSelfPayAuthority) : QuantumSelfPayAction()
}

/** Caller authenticates state and any CP48 at the same fresh checkpoint.
 * PRIMARY execution and RESCUE migration to READY both require eligible CP48.
 * A raw policy cell is structurally checked here, but is not a proof capability.
 */
class TosQuantumSelfPayRequest(
    wallet: AddrStd, state: QuantumSelfPayState, val role: QuantumAuthRole,
    validUntil: Long, action: QuantumSelfPayAction, provenTime: Long, primaryPolicy: Cell? = null
) {
    val request: Cell
    val digest: ByteArray get() = request.hash().toByteArray()
    val context: String get() = if (role == QuantumAuthRole.PRIMARY) "TOS-QSP-MLDSA44-v1" else "TOS-QSP-SLH128S-v1"
    init {
        require(wallet.workchainId == 0 && wallet.anycast.value == null)
        require(provenTime in 0..0xffffffffL && validUntil in 0..0xffffffffL && validUntil - provenTime in 1..3600)
        val nonce = if (role == QuantumAuthRole.PRIMARY) state.primaryNonce else state.rescueNonce
        val kind: Int
        val payload: Cell
        when (action) {
            is QuantumSelfPayAction.Execute -> {
                require(state.seqno < 0xffffffffL && nonce != ULong.MAX_VALUE) { "Execute counter exhausted" }
                TosQuantumAuth.validateActions(action.actions)
                kind = 0; payload = buildCell { storeUInt(0x45584543, 32); storeRef(action.actions) }
            }
            QuantumSelfPayAction.ConfigureRequired -> {
                require(state.seqno < 0xffffffffL)
                kind = 1; payload = buildCell { storeUInt(0x434f4e46, 32); storeUInt(2, 2) }
            }
            QuantumSelfPayAction.LockPrimary -> {
                kind = 3; payload = buildCell { storeUInt(0x4c4f434b, 32); storeUInt(1, 8) }
            }
            is QuantumSelfPayAction.Migrate -> {
                require(action.successor.globalId == state.authority.globalId &&
                    action.successor.namespace.contentEquals(state.authority.namespace))
                require(state.retired == 0 || action.successor.policy == QuantumPolicy.REQUIRED)
                kind = 4; payload = buildCell { storeUInt(0x4d494752, 32); storeRef(action.successor.cell) }
            }
        }
        if (role == QuantumAuthRole.PRIMARY) {
            require(kind == 0 && state.retired == 0 && state.authority.policy == QuantumPolicy.READY) { "PRIMARY unavailable" }
        }
        if (kind != 0) require(role == QuantumAuthRole.RESCUE && state.epoch != ULong.MAX_VALUE)
        if (role == QuantumAuthRole.PRIMARY ||
            (action is QuantumSelfPayAction.Migrate && action.successor.policy == QuantumPolicy.READY)) {
            TosQuantumRetirementPolicy.requirePrimary(requireNotNull(primaryPolicy) { "Eligible CP48 is required" },
                state.authority.namespace, provenTime)
        }
        request = buildCell {
            storeUInt(0x51535031, 32); storeInt(state.authority.globalId, 32); storeBytes(state.authority.namespace)
            storeAddress(wallet); storeBytes(state.authority.hash); storeUInt(role.id, 8)
            storeUInt(BigInteger(state.epoch.toString()), 64); storeUInt(BigInteger(nonce.toString()), 64)
            storeUInt(validUntil, 32); storeUInt(kind, 8); storeRef(payload)
        }
    }
    fun submission(signature: ByteArray): Cell {
        require(signature.size == role.signatureSize)
        return buildCell { storeRef(request); storeRef(TosPqWallet.byteChain(signature)) }
    }
}

/** Authenticated account proof acquisition is required before checking this receipt. */
class TosQuantumSelfPayProbe(
    wallet: AddrStd, authority: QuantumSelfPayAuthority, challenge: ByteArray, validUntil: Long, provenTime: Long
) {
    val code: Cell = TosQuantumSelfPayCode.probe
    val immutable: Cell
    val initialData: Cell
    val stateInit: Cell
    val address: AddrStd
    val digest: ByteArray get() = buildCell { storeUInt(0x51505031, 32); storeBytes(code.hash().toByteArray()); storeRef(immutable) }.hash().toByteArray()
    init {
        require(wallet.workchainId == 0 && wallet.anycast.value == null)
        require(challenge.size == 32 && challenge.any { it != 0.toByte() })
        require(provenTime in 0..0xffffffffL && validUntil in 0..0xffffffffL && validUntil - provenTime in 1..3600)
        immutable = buildCell {
            storeUInt(0x51505031, 32); storeInt(authority.globalId, 32); storeBytes(authority.namespace)
            storeAddress(wallet); storeBytes(challenge); storeUInt(validUntil, 32); storeRef(authority.cell)
        }
        initialData = buildCell { storeUInt(1, 8); storeBit(false); storeRef(immutable) }
        stateInit = TosPqWallet.stateInit(code, initialData)
        address = AddrStd(0, stateInit.hash())
    }
    fun submission(primarySignature: ByteArray, rescueSignature: ByteArray): Cell {
        require(primarySignature.size == 2420 && rescueSignature.size == 7856)
        return buildCell { storeRef(TosPqWallet.byteChain(primarySignature)); storeRef(TosPqWallet.byteChain(rescueSignature)) }
    }
    fun requireVerified(account: AddrStd, installedCode: Cell, installedData: Cell) {
        require(account == address && installedCode.hash() == code.hash()) { "Probe birth identity mismatch" }
        QuantumSelfPayAuthority.ordinary(installedData)
        val s = installedData.beginParse()
        require(s.remainingBits == 9 && s.refs.size == 1 && s.loadUInt(8).toInt() == 1 && s.loadBit()) { "Probe is not verified" }
        require(s.loadRef().hash() == immutable.hash()) { "Probe identity mismatch" }
    }
}
