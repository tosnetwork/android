package network.tos.wallet.data.account.pq

import network.tos.blockchain.ton.contract.QuantumSelfPayState
import network.tos.blockchain.ton.contract.TosQuantumAccountState
import network.tos.blockchain.ton.contract.TosQuantumSelfPayIdentity
import network.tos.blockchain.ton.contract.TosQuantumSelfPayWallet
import network.tos.blockchain.ton.extensions.loadAddress
import network.tos.security.pq.QuantumVerifiedRead
import org.ton.boc.BagOfCells
import org.ton.cell.CellType
import java.math.BigInteger

/** Fresh account observation bound to retained birth identity, not signing authority,
 * custody, gas affordability or transaction success. REQUIRED/rescue reads need no CP48.
 */
class QuantumSelfPayAccount private constructor(
    private val proof: QuantumVerifiedRead, private val observed: Snapshot,
    private val shardTime: Long, private val maximumAge: Long
) {
    sealed class Status {
        data object Absent : Status()
        data object Uninitialized : Status()
        class Frozen(hash: ByteArray) : Status() {
            private val bytes = hash.copyOf()
            val stateHash: ByteArray get() = bytes.copyOf()
        }
        class Active(val state: QuantumSelfPayState) : Status()
    }
    /** Raw recorded balance/debt; pending fees must still be calculated before spending. */
    data class Snapshot(val status: Status, val balance: BigInteger, val storageDebt: BigInteger, val lastPaid: Long)

    fun snapshot(now: Long): Snapshot {
        proof.requireLive(now, maximumAge)
        validateTime(proof.masterchainTime, shardTime, now, maximumAge)
        return observed
    }

    companion object {
        internal fun validateTime(master: Long, shard: Long, now: Long, maximumAge: Long) {
            require(master in 0..0xffffffffL && shard in 0..0xffffffffL && now > 0 && maximumAge in 1..3599)
            check(shard <= master && now - shard in -60..maximumAge) { "Account proof freshness refused" }
        }

        fun bind(identity: TosQuantumSelfPayIdentity, proof: QuantumVerifiedRead, now: Long, maximumAge: Long): QuantumSelfPayAccount {
            require(maximumAge in 1..3599)
            proof.requireLive(now, maximumAge)
            val address = "0:" + identity.address.address.toByteArray().joinToString("") { "%02x".format(it) }
            val observed = decode(proof.provenAccountState(address), identity.wallet)
            return QuantumSelfPayAccount(proof, observed, proof.provenAccountTime(address), maximumAge).also { it.snapshot(now) }
        }

        // Pure codec only. The proof-only bind function mints the account capability.
        internal fun decode(boc: ByteArray?, wallet: TosQuantumSelfPayWallet): Snapshot {
            if (boc == null) return Snapshot(Status.Absent, BigInteger.ZERO, BigInteger.ZERO, 0)
            require(boc.size in 1..67_108_864)
            val root = BagOfCells(boc).roots.single()
            require(root.type == CellType.ORDINARY && root.levelMask.level == 0)
            val s = root.beginParse()
            require(s.loadBit()) { "Present account expected" }
            val address = s.loadAddress()
            require(address == wallet.address) { "Account address mismatch" }
            repeat(2) {
                val count = s.loadUInt(3).toInt()
                require(count < 7)
                s.loadBits(count * 8)
            }
            when (s.loadUInt(3).toInt()) {
                0 -> Unit
                1 -> s.loadBits(256)
                else -> error("Unknown storage extra info")
            }
            fun coins(): BigInteger {
                val count = s.loadUInt(4).toInt()
                return if (count == 0) BigInteger.ZERO else BigInteger(1, s.loadBits(count * 8).toByteArray())
            }
            val lastPaid = s.loadUInt(32).toLong()
            val debt = if (s.loadBit()) coins() else BigInteger.ZERO
            s.loadBits(64)
            val balance = coins()
            if (s.loadBit()) s.loadRef()
            if (s.loadBit()) {
                val active = TosQuantumAccountState.snapshot(boc, wallet.address, wallet.code)
                val state = wallet.readState(wallet.address, wallet.code, active.data)
                return Snapshot(Status.Active(state), active.balance, active.storageDebt, active.lastPaid)
            }
            val status = if (s.loadBit()) Status.Frozen(s.loadBits(256).toByteArray()) else Status.Uninitialized
            require(s.remainingBits == 0 && s.refsPosition == s.refs.size) { "Trailing account data" }
            return Snapshot(status, balance, debt, lastPaid)
        }
    }
}
