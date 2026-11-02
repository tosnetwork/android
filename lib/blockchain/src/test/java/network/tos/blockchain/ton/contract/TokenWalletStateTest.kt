package network.tos.blockchain.ton.contract

import java.math.BigInteger
import network.tos.blockchain.ton.extensions.storeAddress
import org.junit.Assert.*
import org.junit.Test
import org.ton.boc.BagOfCells
import org.ton.block.AddrStd
import org.ton.cell.Cell
import org.ton.cell.buildCell

class TokenWalletStateTest {
    private val owner = AddrStd.parse("0:" + "11".repeat(32))
    private val master = AddrStd.parse("0:" + "22".repeat(32))
    private val code = buildCell { storeUInt(1, 8) }
    private val otherCode = buildCell { storeUInt(2, 8) }
    private val wallet = TokenWalletState.address(owner, master, code)
    private fun data(amount: BigInteger = BigInteger.ZERO, accountOwner: AddrStd = owner,
                     accountMaster: AddrStd = master, storedCode: Cell = code, trailing: Boolean = false) = buildCell {
        val bytes = (amount.bitLength() + 7) / 8
        storeUInt(bytes, 4); if (bytes > 0) storeUInt(amount, bytes * 8)
        storeAddress(accountOwner); storeAddress(accountMaster); storeRef(storedCode)
        if (trailing) storeBit(false)
    }
    @Test fun preservesZeroAndMaximumUnsignedBalance() {
        for (value in listOf(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE.shiftLeft(120) - BigInteger.ONE)) {
            val state = TokenWalletState.decode(data(value), wallet, owner, master, code)
            assertEquals(value, state.balance)
            assertEquals(owner, state.owner); assertEquals(master, state.master)
        }
    }
    @Test fun wrongOwnerMasterCodeAndTrailingStorageAreRefused() {
        for (value in listOf(data(accountOwner = master), data(accountMaster = owner), data(storedCode = otherCode), data(trailing = true))) {
            assertThrows(IllegalArgumentException::class.java) { TokenWalletState.decode(value, wallet, owner, master, code) }
        }
        assertThrows(IllegalArgumentException::class.java) { TokenWalletState.decode(data(), owner, owner, master, code) }
        assertThrows(IllegalArgumentException::class.java) { TokenWalletState.decode(data(), wallet, owner, master, otherCode) }
    }
    @Test fun addressDerivationBindsBothIdentitiesAndCode() {
        assertNotEquals(wallet, TokenWalletState.address(master, master, code))
        assertNotEquals(wallet, TokenWalletState.address(owner, owner, code))
        assertNotEquals(wallet, TokenWalletState.address(owner, master, otherCode))
        assertThrows(IllegalArgumentException::class.java) {
            TokenWalletState.address(AddrStd.parse("-1:" + "11".repeat(32)), master, code)
        }
    }
    @Test fun standardTokenAddressMatchesIndependentFiftVector() {
        fun fixture(name: String) = BagOfCells(checkNotNull(javaClass.classLoader?.getResourceAsStream(name)).use { it.readBytes() }).roots.single()
        val code = fixture("token-wallet-code.boc")
        val expected = AddrStd.parse("0:4377841593b86fb71d29b1306cebd8b1dcaf7071e4a32ed206c5932388c60dae")
        assertEquals(expected, TokenWalletState.address(owner, master, code))
        assertEquals(BigInteger.ZERO, TokenWalletState.decode(fixture("token-wallet-data.boc"), expected, owner, master, code).balance)
    }

}
