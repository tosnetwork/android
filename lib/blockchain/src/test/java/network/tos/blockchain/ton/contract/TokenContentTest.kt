package network.tos.blockchain.ton.contract

import java.math.BigInteger
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test
import org.ton.boc.BagOfCells
import org.ton.cell.Cell
import org.ton.cell.buildCell

class TokenContentTest {
    private fun fixture(name: String): Cell = BagOfCells(checkNotNull(javaClass.classLoader?.getResourceAsStream(name)).use { it.readBytes() }).roots.single()
    private fun snake(text: String): Cell = buildCell { storeUInt(0, 8); storeBytes(text.toByteArray()) }
    private fun metadata(field: String, value: Cell): Cell {
        val key = MessageDigest.getInstance("SHA-256").digest(field.toByteArray())
        val root = buildCell { storeUInt(2, 2); storeUInt(256, 9); storeBytes(key); storeRef(value) }
        return buildCell { storeUInt(0, 8); storeBit(true); storeRef(root) }
    }
    @Test fun independentFiftContentPreservesFieldsAndUnresolvedURI() {
        val local = TokenContent.decode(fixture("token-content-onchain.boc"))
        assertEquals("Sample Credit", local.name); assertEquals("CR", local.symbol); assertEquals(6, local.decimals); assertNull(local.uri)
        val remote = TokenContent.decode(fixture("token-content-uri.boc"))
        assertEquals("https://example.invalid/token.json", remote.uri); assertNull(remote.decimals)
        assertNull(remote.name); assertNull(remote.symbol)
    }
    @Test fun precisionIsBoundedAndAnEmptyOnchainDictionaryDefaultsToNine() {
        assertEquals(9, TokenContent.decode(buildCell { storeUInt(0, 8); storeBit(false) }).decimals)
        for (value in listOf("0", "6", "09", "18", "255")) assertEquals(value.toInt(), TokenContent.decode(metadata("decimals", snake(value))).decimals)
        for (value in listOf("-1", "+1", "256", "1e2", "6.0", " 6", "١")) assertTrue(value, runCatching { TokenContent.decode(metadata("decimals", snake(value))) }.isFailure)
        val hybrid = TokenContent.decode(metadata("uri", snake("https://example.invalid/hybrid.json")))
        assertNull(hybrid.decimals)
    }
    @Test fun malformedUTF8TrailingDataAndOversizedOrDeepSnakesAreRefused() {
        val empty = buildCell { }
        var deep = buildCell { storeBytes("https://example.invalid/deep.json".toByteArray()) }
        repeat(256) { val previous = deep; deep = buildCell { storeRef(previous) } }
        val oversized = TosPqWallet.byteChain(ByteArray(2049) { 65 })
        val invalid = listOf(
            buildCell { storeUInt(2, 8) },
            buildCell { storeUInt(0, 8); storeBit(false); storeBit(false) },
            buildCell { storeUInt(1, 8); storeBytes(byteArrayOf(0xff.toByte())) },
            buildCell { storeUInt(1, 8); storeRef(empty); storeRef(empty) },
            buildCell { storeUInt(1, 8); storeRef(oversized) },
            buildCell { storeUInt(1, 8); storeRef(deep) })
        invalid.forEach { assertTrue(runCatching { TokenContent.decode(it) }.isFailure) }
    }
    @Test fun dictionaryLengthAndUTF8SplitAcrossSnakeCellsAreChecked() {
        val malformed = buildCell { storeUInt(2, 2); storeUInt(257, 9) }
        assertTrue(runCatching { TokenContent.decode(buildCell { storeUInt(0, 8); storeBit(true); storeRef(malformed) }) }.isFailure)
        val tail = buildCell { storeBytes(byteArrayOf(0x95.toByte(), 0x8c.toByte())) }
        val value = buildCell { storeUInt(0, 8); storeBytes(byteArrayOf(0xe7.toByte())); storeRef(tail) }
        assertEquals("界", TokenContent.decode(metadata("name", value)).name)
    }
    @Test fun repeatedBitDictionaryLabelsAreDecodedWithoutConsumingLiteralBits() {
        val key = MessageDigest.getInstance("SHA-256").digest("decimals".toByteArray())
        val value = snake("6")
        val tail = buildCell { storeBytes(key) }.beginParse().also { it.loadBits(2) }.loadBits(254)
        val leaf = buildCell { storeUInt(2, 2); storeUInt(254, 8); storeBits(tail); storeRef(value) }
        val dummy = buildCell { storeUInt(2, 2); storeUInt(254, 8); storeUInt(BigInteger.ZERO, 254); storeRef(value) }
        val root = buildCell {
            storeUInt(3, 2); storeBit(key[0].toInt() and 128 != 0); storeUInt(1, 9)
            if (key[0].toInt() and 64 != 0) { storeRef(dummy); storeRef(leaf) } else { storeRef(leaf); storeRef(dummy) }
        }
        assertEquals(6, TokenContent.decode(buildCell { storeUInt(0, 8); storeBit(true); storeRef(root) }).decimals)
    }
}
