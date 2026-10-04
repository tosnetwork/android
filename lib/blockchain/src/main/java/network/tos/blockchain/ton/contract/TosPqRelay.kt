package network.tos.blockchain.ton.contract

import org.ton.block.AddrStd
import org.ton.cell.Cell
import org.ton.cell.buildCell
import network.tos.blockchain.ton.extensions.storeAddress
import network.tos.blockchain.ton.extensions.loadAddress

/** Typed fee transport. A classical payer never obtains authority over PQ funds. */
class TosPqRelay(private val wallet: TosPqWallet) {
    class Plan private constructor(val messages: List<Cell>, internal val account: AddrStd) {
        companion object {
            internal fun create(messages: List<Cell>, account: AddrStd) = Plan(java.util.Collections.unmodifiableList(messages.toList()), account)
        }
    }
    fun deployment(moduleFunding: Long, walletFunding: Long): Plan = Plan.create(listOf(
        message(wallet.moduleAddress, moduleFunding, wallet.moduleStateInit, Cell.empty()),
        message(wallet.address, walletFunding, wallet.walletStateInit, Cell.empty())), wallet.address)
    fun submission(request: Cell, signature: ByteArray, funding: Long, verify: (ByteArray, ByteArray) -> Boolean): Plan {
        val slice = request.beginParse()
        require(slice.loadInt(32).toInt() == wallet.network && slice.loadAddress() == wallet.address) { "PQ target/network mismatch" }
        slice.loadUInt(64); slice.loadUInt(64); slice.loadUInt(32)
        require(slice.loadUInt(8).toInt() == 0 && slice.bitsPosition == slice.bits.size && slice.refs.size - slice.refsPosition == 1)
        require(verify(wallet.signingMessage(request), signature)) { "PQ signature rejected" }
        return Plan.create(listOf(message(wallet.moduleAddress, funding, null, wallet.submission(request, signature))), wallet.address)
    }
    fun feeSigningMessage(plan: Plan, network: Int, seqno: Int, now: Long, validUntil: Long): Cell {
        require(network == wallet.network && plan.account == wallet.address && plan.messages.size in 1..2 &&
            seqno >= 0 && now in 1..0xffffffffL && validUntil > now && validUntil <= now + 600 && validUntil <= 0xffffffffL)
        var actions = Cell.empty()
        for (raw in plan.messages) actions = buildCell {
            storeUInt(0x0ec3c86d, 32); storeUInt(3, 8); storeRef(actions); storeRef(raw)
        }
        return buildCell {
            storeUInt(0x7369676e, 32); storeInt(network, 32); storeUInt(0, 32)
            storeUInt(validUntil, 32); storeUInt(seqno, 32); storeBit(true); storeRef(actions); storeBit(false)
        }
    }
    private fun message(to: AddrStd, value: Long, init: Cell?, body: Cell): Cell {
        require(value > 0)
        return buildCell {
            storeUInt(16, 6); storeAddress(to)
            val size = (64 - java.lang.Long.numberOfLeadingZeros(value) + 7) / 8
            storeUInt(size, 4); storeUInt(value, size * 8)
            storeBit(false); storeUInt(0, 4); storeUInt(0, 4); storeUInt(0, 64); storeUInt(0, 32)
            storeBit(init != null)
            if (init != null) { storeBit(true); storeRef(init) }
            storeBit(true); storeRef(body)
        }
    }
}
