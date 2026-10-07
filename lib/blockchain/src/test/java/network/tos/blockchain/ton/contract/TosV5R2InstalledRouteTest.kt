package network.tos.blockchain.ton.contract

import java.nio.ByteBuffer
import network.tos.blockchain.ton.extensions.loadAddress
import org.junit.Assert.*
import org.junit.Test
import org.ton.cell.buildCell

class TosV5R2InstalledRouteTest {
    private fun byte(n: Int) = buildCell { storeUInt(n, 8) }
    private fun birth(network: Int = 0x42, global: Int = 1, moduleCode: Int = 2, vaultCode: Int = 3, tree: Int = 0): TosV5R2Genesis {
        val codes = V5R2Codes(byte(1), byte(moduleCode), byte(vaultCode))
        val pins = V5R2CodePins(codes.wallet.hash().toByteArray(), codes.module.hash().toByteArray(), codes.vault.hash().toByteArray())
        val fee = ByteArray(60).also { ByteBuffer.wrap(it).putInt(1).putInt(8).putInt(3) }
        return TosV5R2Genesis(codes, pins, global, ByteArray(32) { network.toByte() }, 17, ByteArray(1312) { tree.toByte() },
            ByteArray(32) { tree.toByte() }, V5R2Policy.READY, ByteArray(32) { tree.toByte() }, fee, 100)
    }
    @Test fun initialAndSuccessorRoutesKeepOriginalWallet() {
        val original = birth(); val next = birth(tree = 1)
        assertEquals(original.vaultInit.hash(), TosV5R2InstalledRoute.initial(original).vaultInit.hash())
        val route = TosV5R2InstalledRoute.successor(original, next)
        assertEquals(next.moduleInit.hash(), route.moduleInit.hash())
        assertEquals("Successor vault changed original wallet", next.successorFor(original.address).first.hash(), route.vaultInit.hash())
        assertNotEquals(next.vaultInit.hash(), route.vaultInit.hash())
        val prefix = route.vaultData.refs[1].beginParse(); prefix.loadBits(32 + 32 + 256)
        assertEquals(original.address, prefix.loadAddress())
    }
    @Test fun namespaceAndCodeChangesRefuse() {
        for (next in listOf(birth(network = 0x43), birth(global = 2), birth(moduleCode = 9), birth(vaultCode = 9)))
            assertTrue("Incompatible successor accepted", runCatching { TosV5R2InstalledRoute.successor(birth(), next) }.isFailure)
    }
    @Test fun custodyMustFollowCurrentModuleInsteadOfBirthKey() {
        val original = birth(); val next = birth(tree = 1)
        val initial = TosV5R2InstalledRoute.initial(original)
        initial.requirePrimaryKey(ByteArray(1312))
        val current = TosV5R2InstalledRoute.successor(original, next)
        current.requirePrimaryKey(ByteArray(1312) { 1 })
        assertTrue("Birth key accepted after rotation", runCatching { current.requirePrimaryKey(ByteArray(1312)) }.isFailure)
        assertTrue("Wrong key size accepted", runCatching { current.requirePrimaryKey(ByteArray(32)) }.isFailure)
    }
}
