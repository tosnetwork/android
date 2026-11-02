package network.tos.blockchain.ton.contract

import java.math.BigInteger
import network.tos.blockchain.ton.extensions.storeAddress
import org.junit.Assert.*
import org.junit.Test
import org.ton.block.AddrStd
import org.ton.boc.BagOfCells
import org.ton.cell.Cell
import org.ton.cell.buildCell

class TokenMasterStateTest {
    private val code = TokenContractCodes.referenceWallet
    private val content = buildCell { storeUInt(1, 8); storeBytes("https://example.invalid/token.json".toByteArray()) }
    private fun data(supply: BigInteger = BigInteger.ZERO, admin: Boolean = false,
                     walletCode: Cell = code, trailing: Boolean = false) = buildCell {
        val bytes = (supply.bitLength() + 7) / 8
        storeUInt(bytes, 4); if (bytes > 0) storeUInt(supply, bytes * 8)
        if (admin) storeAddress(AddrStd.parse("0:" + "33".repeat(32))) else storeUInt(0, 2)
        storeRef(content); storeRef(walletCode)
        if (trailing) storeRef(content)
    }
    @Test fun supplyAndContentSurviveMintAdminRenunciation() {
        val maximum = BigInteger.ONE.shiftLeft(120) - BigInteger.ONE
        for (admin in listOf(false, true)) for (supply in listOf(BigInteger.ZERO, maximum)) {
            val state = TokenMasterState.decode(data(supply, admin), code)
            assertEquals(supply, state.totalSupply); assertEquals(content.hash(), state.content.hash())
        }
    }
    @Test fun wrongWalletCodeAndTrailingStorageAreRejected() {
        for (cell in listOf(data(walletCode = buildCell { }), data(trailing = true)))
            assertTrue(runCatching { TokenMasterState.decode(cell, code) }.isFailure)
    }
    @Test fun runtimeReferenceWalletPinMatchesIndependentFixture() {
        val fixture = checkNotNull(javaClass.classLoader?.getResourceAsStream("token-wallet-code.boc")).use { it.readBytes() }
        assertEquals(BagOfCells(fixture).roots.single().hash(), code.hash())
        assertNotEquals(TokenContractCodes.referenceMaster.hash(), code.hash())
    }
    @Test fun nativeFamilyPinsAndDerivedAddressMatchIndependentFift() {
        fun fixture(name: String) = BagOfCells(checkNotNull(javaClass.classLoader?.getResourceAsStream(name)).use { it.readBytes() }).roots.single()
        val owner = AddrStd.parse("0:" + "11".repeat(32)); val master = AddrStd.parse("0:" + "22".repeat(32))
        val wallet = AddrStd.parse("0:5c2e7defab4c288bc81524d1594af7a14a984c4d789ac2acf28a4e8b589f9293")
        assertEquals(fixture("token-wallet-tol.boc").hash(), TokenContractCodes.nativeWallet.hash())
        assertEquals(fixture("token-minter-tol.boc").hash(), TokenContractCodes.nativeMaster.hash())
        assertEquals(wallet, TokenWalletState.address(owner, master, TokenContractCodes.nativeWallet))
        assertEquals(BigInteger.ZERO, TokenWalletState.decode(fixture("token-native-wallet-data.boc"), wallet, owner, master, TokenContractCodes.nativeWallet).balance)
        assertEquals("native-tol-v1", TokenContractCodes.family(owner, master, wallet).name)
        val reference = TokenWalletState.address(owner, master, TokenContractCodes.referenceWallet)
        assertEquals("sdk-func-v1", TokenContractCodes.family(owner, master, reference).name)
        assertThrows(IllegalArgumentException::class.java) { TokenContractCodes.family(master, master, wallet) }
    }

}
