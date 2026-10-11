package network.tos.blockchain.ton.contract

import java.nio.ByteBuffer
import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Test
import org.ton.cell.Cell
import org.ton.cell.buildCell

class TosQuantumWalletDataTest {
    private fun code(n: Int) = buildCell { storeUInt(n, 8) }
    private val codes = QuantumCodes(code(1), code(2), code(3))
    private val pins = QuantumCodePins(codes.wallet.hash().toByteArray(), codes.module.hash().toByteArray(), codes.vault.hash().toByteArray())
    private val birth = TosQuantumGenesis(codes, pins, 1, ByteArray(32) { 0x42 }, 17,
        ByteArray(1312), ByteArray(32), QuantumPolicy.READY, ByteArray(32),
        ByteArray(60).also { ByteBuffer.wrap(it).putInt(1).putInt(8).putInt(3) }, 100)
    private fun state(classic: Boolean = false, key: ByteArray = ByteArray(32), extensions: Boolean = false,
                      id: Long = 17, version: Int = 4, mode: Int = 2, module: Cell = birth.moduleInit,
                      metadata: Cell = birth.metadata, epoch: ULong = 1uL, nonce: ULong = 0uL,
                      trailing: Boolean = false, extraRef: Boolean = false) = buildCell {
        storeBit(classic); storeUInt(0xffffffffL, 32); storeUInt(id, 32); storeBytes(key); storeBit(extensions)
        storeRef(buildCell {
            storeUInt(version, 8); storeUInt(mode, 2); storeUInt(0xffff, 16)
            storeUInt(BigInteger(epoch.toString()), 64); storeUInt(BigInteger(nonce.toString()), 64); storeUInt(BigInteger(nonce.toString()), 64)
            storeRef(module); storeRef(metadata)
        })
        if (trailing) storeBit(false)
        if (extraRef) storeRef(code(99))
    }
    @Test fun initialAndUnsignedTerminalCountersDecodeWithoutWrapping() {
        val initial = TosQuantumWalletData.parse(birth.walletData, birth)
        assertEquals(0L, initial.seqno); assertEquals(1uL, initial.epoch)
        val saturated = TosQuantumWalletData.parse(state(epoch = ULong.MAX_VALUE, nonce = ULong.MAX_VALUE), birth)
        assertEquals(0xffffffffL, saturated.seqno); assertEquals(0xffff, saturated.retired)
        assertEquals(ULong.MAX_VALUE, saturated.epoch); assertEquals(ULong.MAX_VALUE, saturated.primaryNonce)
        assertEquals(ULong.MAX_VALUE, saturated.rescueNonce)
    }
    @Test fun classicAndLegacyFieldsNeverAuthorizeR2() {
        for (cell in listOf(state(classic = true), state(key = ByteArray(32) { 1 }), state(extensions = true)))
            assertTrue("Classic state accepted", runCatching { TosQuantumWalletData.parse(cell, birth) }.isFailure)
    }
    @Test fun exactVersionModeIdentityAndTupleRequired() {
        for (cell in listOf(state(version = 3), state(mode = 0), state(mode = 1), state(mode = 3),
                            state(id = 18), state(module = code(99)), state(metadata = code(99)),
                            state(trailing = true), state(extraRef = true)))
            assertTrue("Mismatched R2 state accepted", runCatching { TosQuantumWalletData.parse(cell, birth) }.isFailure)
    }
}
