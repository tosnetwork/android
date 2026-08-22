package network.tos.blockchain.ton.dns

import org.ton.cell.Cell
import org.ton.cell.CellBuilder
import java.math.BigInteger

/** Byte-exact message bodies for the TIP-1 DNS Collection/Domain Item ABI. */
object TosDnsOperation {
    const val OP_GET_STATIC_DATA = 0x2fcb26a2L
    const val OP_CHANGE_DNS_RECORD = 0x4eb1f0f9L
    const val OP_BALANCE_RELEASE = 0x4ed14b65L

    const val AUCTION_START_TIME = 1_798_761_600L
    const val PERIOD_SECONDS = 2_592_000L
    const val INITIAL_AUCTION_SECONDS = 604_800L
    const val FINAL_AUCTION_SECONDS = 3_600L
    const val AUCTION_PROLONGATION_SECONDS = 3_600L
    const val LEASE_SECONDS = 31_622_400L
    val ONE_TOS: BigInteger = BigInteger("1000000000")
    val CONTRACT_ACTION_VALUE: BigInteger = BigInteger("100000000")

    fun registerBody(label: String): Cell {
        validateRegistrationLabel(label)
        val bytes = label.encodeToByteArray()
        return CellBuilder.createCell {
            storeUInt(0, 32)
            val headSize = minOf(bytes.size, 123)
            storeBytes(bytes.copyOfRange(0, headSize))
            if (headSize < bytes.size) {
                storeRef(CellBuilder.createCell { storeBytes(bytes.copyOfRange(headSize, bytes.size)) })
            }
        }
    }

    /** An empty body is both an auction bid and an owner top-up. */
    fun bidOrTopUpBody(): Cell = CellBuilder.createCell {}

    fun finishAuctionBody(queryId: BigInteger = BigInteger.ZERO): Cell = queryBody(
        OP_GET_STATIC_DATA,
        queryId,
    )

    fun releaseBody(queryId: BigInteger = BigInteger.ZERO): Cell = queryBody(
        OP_BALANCE_RELEASE,
        queryId,
    )

    fun changeRecordBody(
        category: BigInteger,
        value: Cell?,
        queryId: BigInteger = BigInteger.ZERO,
    ): Cell {
        require(category.signum() >= 0 && category.bitLength() <= 256) { "DNS category must fit uint256" }
        require(queryId.signum() >= 0 && queryId.bitLength() <= 64) { "queryId must fit uint64" }
        return CellBuilder.createCell {
            storeUInt(OP_CHANGE_DNS_RECORD, 32)
            storeUInt(queryId, 64)
            storeUInt(category, 256)
            value?.let(::storeRef)
        }
    }

    fun minimumNextBid(currentMaximum: BigInteger): BigInteger {
        require(currentMaximum.signum() >= 0)
        return currentMaximum.multiply(BigInteger.valueOf(105)).divide(BigInteger.valueOf(100))
    }

    fun minimumPrice(labelBytes: Int, now: Long, auctionStart: Long = AUCTION_START_TIME): BigInteger {
        require(labelBytes in 4..126)
        val (initial, floor) = when (labelBytes) {
            4 -> 1_000L to 100L
            5 -> 500L to 50L
            6 -> 400L to 40L
            7 -> 300L to 30L
            8 -> 200L to 20L
            9 -> 100L to 10L
            10 -> 50L to 5L
            else -> 10L to 1L
        }
        var value = BigInteger.valueOf(initial).multiply(ONE_TOS)
        val floorValue = BigInteger.valueOf(floor).multiply(ONE_TOS)
        val periods = Math.floorDiv(now - auctionStart, PERIOD_SECONDS)
        if (periods > 21) return floorValue
        repeat(maxOf(0L, periods).toInt()) {
            value = value.multiply(BigInteger.valueOf(90)).divide(BigInteger.valueOf(100))
        }
        return value
    }

    fun initialAuctionDuration(now: Long, auctionStart: Long = AUCTION_START_TIME): Long {
        require(now > auctionStart) { "DNS registration has not launched" }
        val periods = minOf(12L, Math.floorDiv(now - auctionStart, PERIOD_SECONDS))
        return INITIAL_AUCTION_SECONDS -
            ((INITIAL_AUCTION_SECONDS - FINAL_AUCTION_SECONDS) * periods / 12L)
    }

    fun prolongedEndTime(currentEnd: Long, now: Long): Long =
        maxOf(currentEnd, now + AUCTION_PROLONGATION_SECONDS)

    fun renewalDeadline(lastFillUpTime: Long): Long {
        require(lastFillUpTime > 0 && lastFillUpTime <= Long.MAX_VALUE - LEASE_SECONDS)
        return lastFillUpTime + LEASE_SECONDS
    }

    private fun queryBody(opcode: Long, queryId: BigInteger): Cell {
        require(queryId.signum() >= 0 && queryId.bitLength() <= 64) { "queryId must fit uint64" }
        return CellBuilder.createCell {
            storeUInt(opcode, 32)
            storeUInt(queryId, 64)
        }
    }

    private fun validateRegistrationLabel(label: String) {
        val bytes = label.encodeToByteArray()
        require(bytes.size in 4..126 && bytes.first() != '-'.code.toByte() && bytes.last() != '-'.code.toByte() &&
            bytes.all { it in 'a'.code.toByte()..'z'.code.toByte() || it in '0'.code.toByte()..'9'.code.toByte() || it == '-'.code.toByte() }
        ) { "invalid DNS registration label" }
    }
}
