package network.tos.blockchain.ton.contract

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test
import org.ton.block.AddrStd
import org.ton.cell.buildCell
import org.ton.crypto.hex

class TosV5R2GenesisTest {
    private fun hash(n: Int) = ByteArray(32).also { it[30] = (n shr 8).toByte(); it[31] = n.toByte() }
    private fun byte(n: Int) = buildCell { storeUInt(n, 8) }
    private val code = V5R2Codes(byte(1), byte(2), byte(3))
    private val pins = V5R2CodePins(code.wallet.hash().toByteArray(), code.module.hash().toByteArray(), code.vault.hash().toByteArray())
    private fun feeKey() = ByteArray(60).also { ByteBuffer.wrap(it).putInt(1).putInt(8).putInt(3); it.fill(0x33, 12, 28); it.fill(0x44, 28) }
    private fun create(policy: V5R2Policy = V5R2Policy.READY, walletId: Long = 42, tree: Int = 456, key: ByteArray = feeKey(), codes: V5R2Codes = code) =
        TosV5R2Genesis(codes, pins, 42, hash(123), walletId, ByteArray(1312) { 0x11 }, ByteArray(32) { 0x22 }, policy, hash(tree), key, 1779992790)
    // Frozen independent Python/Rust vectors; never refreshed from this implementation.
    private val cases = listOf(
        mapOf("policy" to "1", "wallet_id" to "42", "tree_id" to "456", "module_data" to "f1d1ff72ce84dbeacdfbf0a31ed0a4be15e5423fd9b19133435e723b889da9bc", "module_init" to "043d7e85c0cd0e096fc0bd6816f688cd93e743385c1193ea5e8ddac8fce411f8", "metadata" to "107d0974c49a501a7ddf761b580966fdda4316f176d062ff48f959fabbe26bb6", "wallet_data" to "6320f2e41590635aed8388908b801e0194b85ffdafa20e8cd470d84967c71807", "wallet_init" to "017b4078cbfce4b21954669c79ed5f9785176eae6518e8f3bb9448ed2ab12b46", "vault_data" to "7ecd9d82f218338adb9763f511c1141a2996b50c0774af98cc6408cd716b5e97", "vault_init" to "433e7d2c2b2955c2079a17a276b2331fb7b1efbd469f656adb8a55fa250f7afd", "config_hash" to "1d54665e5652d75d891c35b1c80b09c52b5186c28b2533ed054feb9c51b85876"),
        mapOf("policy" to "2", "wallet_id" to "42", "tree_id" to "456", "module_data" to "21b3efa098e049be0aabda9fd266cdac4744d8e228047c82e951a69c99711ae0", "module_init" to "c34811e4422614bedc4ea883a675ffd4ff18bb77673567f1113f82f5c8ba5a33", "metadata" to "107d0974c49a501a7ddf761b580966fdda4316f176d062ff48f959fabbe26bb6", "wallet_data" to "b0d754362a81298b1dbe06b4c1aeb83cb554b1a52859208063070b7c27b0b84c", "wallet_init" to "5769fe67d7141f63346aba455fcf0eb36a3b55c86021b4a91c0764eee923899e", "vault_data" to "5359d93fa355b44b8d30a5e87d965c0a431724ad11d14b5557c7242c0141aef3", "vault_init" to "f62b41d4271152737e17624e9138eeb8f7b07e00b0b987c5457080c3913592e4", "config_hash" to "e110fcc89ec54ee6e18a38882a7c6bac1f66adff2dd8a330cacc6b4caa355a91"),
        mapOf("policy" to "1", "wallet_id" to "43", "tree_id" to "456", "module_data" to "f1d1ff72ce84dbeacdfbf0a31ed0a4be15e5423fd9b19133435e723b889da9bc", "module_init" to "043d7e85c0cd0e096fc0bd6816f688cd93e743385c1193ea5e8ddac8fce411f8", "metadata" to "107d0974c49a501a7ddf761b580966fdda4316f176d062ff48f959fabbe26bb6", "wallet_data" to "67c95184a1c213232a488a25aff9e3275898b1cce46c5e6a1ba6a76aa358fd6a", "wallet_init" to "86123870dad5229fba84a186e9040f14f182e796be6b2c31a38066c8fc63f5ba", "vault_data" to "99b0d8f5ac2e1740e17b5467e46d84a00418dc8fc53fbd8f9110028ff4c5ecf6", "vault_init" to "089d0ec603f54745f1d9f3f2109761ed307ce6b6704aa30ce7d85d6adfb41339", "config_hash" to "77dd04adce7c35f2a2af90c2938a931664d6c41acfb8ec7294269b30e7b61706"),
        mapOf("policy" to "1", "wallet_id" to "42", "tree_id" to "457", "module_data" to "f1d1ff72ce84dbeacdfbf0a31ed0a4be15e5423fd9b19133435e723b889da9bc", "module_init" to "043d7e85c0cd0e096fc0bd6816f688cd93e743385c1193ea5e8ddac8fce411f8", "metadata" to "8cb16702242436745e2dd6244f4f082cde637a2ef1ed8b4ba0a1013da0046e1b", "wallet_data" to "f99422dfa02e4d50880e2f8ab2d4daf27c2b982faada9c9ec3a3d21379870048", "wallet_init" to "710d84cb20364d617a56c258a9fdf714f5a5eb007318d725ad27aaf2d2c7c4d6", "vault_data" to "1f45ed3975e8802fa35fd269ea47825d5c9fe105d7b818a3c13c77a58dd009a2", "vault_init" to "c6ea7d129c1323435694f07e5a1cd04e568332292c8e2f3081a4065c98278a2f", "config_hash" to "328e42b663d97b88b397796a14aff998a0d656054012cd89875c91e036ca711c")
    )
    @Test fun allInitialStateIdentitiesMatchIndependentVectors() {
        for (row in cases) {
            val w = create(if (row.getValue("policy") == "1") V5R2Policy.READY else V5R2Policy.REQUIRED, row.getValue("wallet_id").toLong(), row.getValue("tree_id").toInt())
            for ((name, cell) in mapOf("module_data" to w.moduleData, "module_init" to w.moduleInit, "metadata" to w.metadata,
                "wallet_data" to w.walletData, "wallet_init" to w.walletInit, "vault_data" to w.vaultData, "vault_init" to w.vaultInit)) {
                assertArrayEquals(name, hex(row.getValue(name)), cell.hash().toByteArray())
            }
            assertArrayEquals(hex(row.getValue("config_hash")), w.configHash)
            val s = w.walletData.beginParse(); assertFalse(s.loadBit()); s.loadUInt(32); s.loadUInt(32)
            assertTrue(s.loadBits(256).toByteArray().all { it == 0.toByte() }); assertFalse(s.loadBit())
        }
    }
    @Test fun independentlyPinnedCodeAndFixedFeeProfileRequired() {
        for (c in listOf(code.copy(wallet = byte(99)), code.copy(module = byte(99)), code.copy(vault = byte(99))))
            assertEquals("V5R2 code pin mismatch", runCatching { create(codes = c) }.exceptionOrNull()?.message)
        for (offset in listOf(0, 4, 8)) {
            val key = feeKey(); ByteBuffer.wrap(key).putInt(offset, 99)
            assertEquals("Fixed HSS L1 H20/W4 required", runCatching { create(key = key) }.exceptionOrNull()?.message)
        }
        for (length in listOf(59, 61)) assertTrue(runCatching { create(key = ByteArray(length)) }.isFailure)
    }
    @Test fun successorTargetsOriginalBasechainWalletAndCannotTargetModule() {
        val w = create(); val wallet = AddrStd(0, hash(100))
        val next = w.successorFor(wallet)
        assertNotEquals(w.vaultInit.hash(), next.first.hash())
        assertTrue(runCatching { w.successorFor(AddrStd(-1, hash(100))) }.isFailure)
        assertTrue(runCatching { w.successorFor(w.moduleAddress) }.isFailure)
        val config = w.configHash; config.fill(0); assertFalse(w.configHash.all { it == 0.toByte() })
    }
}
