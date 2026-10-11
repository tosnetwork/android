package network.tos.blockchain.ton.contract

import java.nio.ByteBuffer
import java.math.BigInteger
import network.tos.blockchain.ton.extensions.storeAddress
import network.tos.blockchain.ton.extensions.toByteArray
import org.junit.Assert.*
import org.junit.Test
import org.ton.block.AddrStd
import org.ton.boc.BagOfCells
import org.ton.cell.Cell
import org.ton.cell.buildCell

class TosQuantumAccountStateTest {
    private fun cell(n: Int) = buildCell { storeUInt(n, 8) }
    private val code = cell(1)
    private val data = cell(2)
    private val address = AddrStd(0, ByteArray(32) { 3 })
    private fun account(extra: Int = 0, accountCode: Cell = code, accountAddress: AddrStd = address,
                        split: Boolean = false, trailing: Boolean = false,
                        balance: ByteArray = byteArrayOf(), debt: ByteArray? = null) = buildCell {
        storeBit(true); storeAddress(accountAddress)
        storeUInt(0, 3); storeUInt(0, 3); storeUInt(extra, 3)
        if (extra == 1) storeBytes(ByteArray(32) { 4 })
        storeUInt(100, 32); storeBit(debt != null)
        debt?.let { storeUInt(it.size, 4); storeBytes(it) }
        storeUInt(0, 64)
        storeUInt(balance.size, 4); storeBytes(balance); storeBit(false); storeBit(true)
        if (split) {
            storeBit(true); storeUInt(1, 5); storeBit(false)
            storeBit(true); storeRef(accountCode); storeBit(true); storeRef(data); storeBit(false)
        } else storeSlice(TosPqWallet.stateInit(accountCode, data).beginParse())
        if (trailing) storeBit(false)
    }
    @Test fun tosStorageExtraNoneAndDictionaryMetadataDecode() {
        for (extra in listOf(0, 1))
            assertEquals(data.hash(), TosQuantumAccountState.data(account(extra).toByteArray(), address, code).hash())
    }
    @Test fun balancesAndDebtPreserveUnsignedCoinsWithoutNarrowing() {
        val max = ByteArray(15) { 0xff.toByte() }
        val snapshot = TosQuantumAccountState.snapshot(account(balance = max, debt = byteArrayOf(0x80.toByte())).toByteArray(), address, code)
        assertEquals(BigInteger.ONE.shiftLeft(120).subtract(BigInteger.ONE), snapshot.balance)
        assertEquals(BigInteger.valueOf(128), snapshot.storageDebt)
        assertEquals(100L, snapshot.lastPaid)
        assertEquals(data.hash(), snapshot.data.hash())
        assertEquals(BigInteger.ZERO, TosQuantumAccountState.snapshot(account().toByteArray(), address, code).balance)
    }
    @Test fun accountIdentityCodeFlagsAndTrailingDataRefused() {
        for (value in listOf(account(extra = 2), account(accountCode = cell(9)),
                             account(accountAddress = AddrStd(0, ByteArray(32) { 9 })),
                             account(split = true), account(trailing = true)))
            assertTrue("Unbound account accepted", runCatching { TosQuantumAccountState.data(value.toByteArray(), address, code) }.isFailure)
        val multiple = BagOfCells(listOf(account(), account(accountAddress = AddrStd(0, ByteArray(32) { 9 })))).toByteArray()
        assertTrue("Multiple account roots accepted", runCatching { TosQuantumAccountState.data(multiple, address, code) }.isFailure)
    }
    private fun birth(tree: Int = 0): TosQuantumGenesis {
        val codes = QuantumCodes(cell(1), cell(2), cell(3))
        val pins = QuantumCodePins(codes.wallet.hash().toByteArray(), codes.module.hash().toByteArray(), codes.vault.hash().toByteArray())
        val fee = ByteArray(60).also { ByteBuffer.wrap(it).putInt(1).putInt(8).putInt(3) }
        return TosQuantumGenesis(codes, pins, 1, ByteArray(32) { 0x42 }, 17, ByteArray(1312), ByteArray(32),
            QuantumPolicy.READY, ByteArray(32) { tree.toByte() }, fee, 100)
    }
    @Test fun feeCounterMayAdvanceThroughTerminalButCannotChangeConfiguration() {
        val expected = birth().vaultData
        fun counted(next: Long): Cell {
            val tail = expected.beginParse(); tail.loadBits(40)
            return buildCell {
                storeUInt(3, 8); storeUInt(next, 32); storeBits(tail.loadBits(tail.remainingBits))
                while (tail.refsPosition < tail.refs.size) storeRef(tail.loadRef())
            }
        }
        for (next in listOf(0L, 1L, 1048575L, 1048576L))
            assertEquals(next, TosQuantumAccountState.vaultCounter(counted(next), expected))
        assertTrue("Exhausted counter overflow accepted", runCatching { TosQuantumAccountState.vaultCounter(counted(1048577), expected) }.isFailure)
        assertTrue("Changed vault configuration accepted", runCatching { TosQuantumAccountState.vaultCounter(birth(1).vaultData, expected) }.isFailure)
        val tail = expected.beginParse(); tail.loadBits(40)
        val changedHash = tail.loadBits(256).toByteArray().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val alteredBits = buildCell {
            storeUInt(3, 8); storeUInt(0, 32); storeBytes(changedHash); storeBits(tail.loadBits(tail.remainingBits))
            while (tail.refsPosition < tail.refs.size) storeRef(tail.loadRef())
        }
        assertTrue("Changed vault bits accepted", runCatching { TosQuantumAccountState.vaultCounter(alteredBits, expected) }.isFailure)
    }
}
