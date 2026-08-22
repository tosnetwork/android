package network.tos.blockchain.ton.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.ton.cell.CellBuilder
import java.math.BigInteger

class TosDnsOperationTest {
    @Test
    fun registrationBodyUsesOpZeroSnakeEncoding() {
        val label = "a".repeat(126)
        val slice = TosDnsOperation.registerBody(label).beginParse()
        assertEquals(BigInteger.ZERO, slice.loadUInt(32))
        val bytes = buildList {
            addAll(slice.loadBits(slice.bits.size - slice.bitsPosition).toByteArray().toList())
            if (slice.refsPosition < slice.refs.size) {
                val tail = slice.loadRef().beginParse()
                addAll(tail.loadBits(tail.bits.size - tail.bitsPosition).toByteArray().toList())
            }
        }.toByteArray()
        assertEquals(label, bytes.decodeToString())
    }

    @Test
    fun registrationRejectsContractInvalidLabels() {
        listOf("abc", "-abcd", "abcd-", "Abcd", "a_bcd", "a".repeat(127)).forEach {
            assertThrows(IllegalArgumentException::class.java) { TosDnsOperation.registerBody(it) }
        }
        TosDnsOperation.registerBody("a--b")
        TosDnsOperation.registerBody("xn--80ak6aa92e")
    }

    @Test
    fun bodiesMatchTip1() {
        var slice = TosDnsOperation.bidOrTopUpBody().beginParse()
        assertEquals(0, slice.bits.size)
        assertEquals(0, slice.refs.size)

        slice = TosDnsOperation.finishAuctionBody(BigInteger.valueOf(7)).beginParse()
        assertEquals(BigInteger.valueOf(0x2fcb26a2), slice.loadUInt(32))
        assertEquals(BigInteger.valueOf(7), slice.loadUInt(64))

        slice = TosDnsOperation.releaseBody(BigInteger.valueOf(9)).beginParse()
        assertEquals(BigInteger.valueOf(0x4ed14b65), slice.loadUInt(32))
        assertEquals(BigInteger.valueOf(9), slice.loadUInt(64))
    }

    @Test
    fun recordSetAndDeleteEncoding() {
        val value = CellBuilder.createCell { storeUInt(0x9fd3, 16) }
        var slice = TosDnsOperation.changeRecordBody(BigInteger.valueOf(42), value, BigInteger.valueOf(11)).beginParse()
        assertEquals(BigInteger.valueOf(0x4eb1f0f9), slice.loadUInt(32))
        assertEquals(BigInteger.valueOf(11), slice.loadUInt(64))
        assertEquals(BigInteger.valueOf(42), slice.loadUInt(256))
        assertEquals(value.hash(), slice.loadRef().hash())

        slice = TosDnsOperation.changeRecordBody(BigInteger.valueOf(42), null).beginParse()
        slice.loadUInt(32)
        slice.loadUInt(64)
        slice.loadUInt(256)
        assertEquals(0, slice.refs.size - slice.refsPosition)
    }

    @Test
    fun auctionArithmeticMatchesCanonicalVectors() {
        val start = TosDnsOperation.AUCTION_START_TIME
        assertEquals(BigInteger("500000000000"), TosDnsOperation.minimumPrice(5, start + 1))
        assertEquals(BigInteger("450000000000"), TosDnsOperation.minimumPrice(5, start + TosDnsOperation.PERIOD_SECONDS))
        assertEquals(BigInteger("100000000001").multiply(BigInteger.valueOf(105)).divide(BigInteger.valueOf(100)),
            TosDnsOperation.minimumNextBid(BigInteger("100000000001")))
        assertEquals(604_800L, TosDnsOperation.initialAuctionDuration(start + 1))
        assertEquals(554_700L, TosDnsOperation.initialAuctionDuration(start + TosDnsOperation.PERIOD_SECONDS))
        assertEquals(1_003_540L, TosDnsOperation.prolongedEndTime(1_000_000L, 999_940L))
        assertEquals(31_623_400L, TosDnsOperation.renewalDeadline(1_000L))
    }
}
