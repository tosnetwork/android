package network.tos.blockchain.ton.contract

import network.tos.blockchain.ton.extensions.storeAddress
import org.junit.Assert.*
import org.junit.Test
import org.ton.block.AddrStd
import org.ton.cell.Cell
import org.ton.cell.buildCell

class TosV5R2PaymentReviewTest {
    private fun message(amount: Int) = buildCell {
        storeUInt(16, 6) // internal, IHR disabled, no bounce, absent source
        storeAddress(AddrStd(0, ByteArray(32) { amount.toByte() }))
        storeUInt(1, 4); storeUInt(amount, 8); storeBit(false)
        storeUInt(0, 4); storeUInt(0, 4); storeUInt(0, 64); storeUInt(0, 32)
        storeBit(false); storeBit(false)
    }
    private fun action(amount: Int, previous: Cell = Cell.empty(), mode: Int = 3) = buildCell {
        storeUInt(0x0ec3c86d, 32); storeUInt(mode, 8); storeRef(previous); storeRef(message(amount))
    }
    @Test fun reviewPreservesExecutionOrderAndExactActionBinding() {
        val actions = action(9, action(7))
        val review = TosV5R2PaymentReview.parse(actions)
        assertEquals(listOf(7, 9), review.transfers.map { it.destination.address.toByteArray()[0].toInt() })
        assertEquals(listOf("7", "9"), review.transfers.map { it.coins.amount.toString() })
        review.requireSameActions(actions)
        review.actionsHash.fill(0); review.requireSameActions(actions)
        assertEquals("Reviewed payment actions changed", runCatching { review.requireSameActions(action(8)) }.exceptionOrNull()?.message)
        assertTrue(runCatching { (review.transfers as MutableList).clear() }.exceptionOrNull() is UnsupportedOperationException)
    }
    @Test fun unrepresentedModesAndEmptyActionsRefuse() {
        assertEquals("Payment review requires fixed-value send mode", runCatching {
            TosV5R2PaymentReview.parse(action(7, mode = 130))
        }.exceptionOrNull()?.message)
        assertEquals("Payment review requires transfers", runCatching {
            TosV5R2PaymentReview.parse(Cell.empty())
        }.exceptionOrNull()?.message)
    }
}
