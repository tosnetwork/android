package network.tos.blockchain.ton.contract

import java.util.Collections
import org.ton.cell.Cell

/** Fully decoded native-payment confirmation, bound to the exact AUTH action list.
 * Contract calls, deployment and balance-carry modes require their own complete
 * confirmation models. This payment model grants no custody, solvency or authority.
 */
class TosV5R2PaymentReview private constructor(val actions: Cell, transfers: List<TosV5Transfer>) {
    val transfers: List<TosV5Transfer> = Collections.unmodifiableList(transfers.toList())
    private val hash = actions.hash().toByteArray()
    val actionsHash: ByteArray get() = hash.copyOf()
    fun requireSameActions(candidate: Cell) {
        require(candidate.hash().toByteArray().contentEquals(hash)) { "Reviewed payment actions changed" }
    }
    companion object {
        fun parse(actions: Cell): TosV5R2PaymentReview {
            TosV5R2Auth.validateActions(actions)
            var current = actions
            val messages = mutableListOf<Cell>()
            while (!current.isEmpty()) {
                val slice = current.beginParse()
                slice.loadUInt(32)
                require(slice.loadUInt(8).toInt() == 3) { "Payment review requires fixed-value send mode" }
                current = slice.loadRef()
                messages.add(slice.loadRef())
            }
            require(messages.isNotEmpty()) { "Payment review requires transfers" }
            return TosV5R2PaymentReview(actions, messages.reversed().map(TosV5SigningRequest::decodeTransfer))
        }
    }
}
