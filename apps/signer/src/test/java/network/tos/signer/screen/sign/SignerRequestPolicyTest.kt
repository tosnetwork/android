package network.tos.signer.screen.sign

import network.tos.blockchain.ton.contract.TosWalletV5R1Contract
import network.tos.blockchain.ton.contract.BaseWalletContract
import network.tos.blockchain.ton.extensions.cellFromBase64
import org.junit.Assert.*
import org.junit.Test
import org.ton.api.pub.PublicKeyEd25519
import org.ton.block.AddrStd
import org.ton.block.Coins
import org.ton.contract.wallet.WalletTransferBuilder

class SignerRequestPolicyTest {
    private val key = PublicKeyEd25519(ByteArray(32) { it.toByte() })
    private val gift = WalletTransferBuilder().apply {
        destination = AddrStd.parse("0:${"11".repeat(32)}"); coins = Coins.ofNano(1L); sendMode = 3
    }.build()
    @Test fun frozenNativeBlindSigningBodiesCannotEscapeByChangingOrOmittingVersion() {
        val rows = requireNotNull(javaClass.getResourceAsStream("/tos-signer-boundary-vectors.tsv"))
            .bufferedReader().use { it.readLines() }.filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(4, rows.size)
        for (row in rows) for (version in listOf("tosv5r1", "v4r2", "v5r1", "unknown")) {
            val body = row.split('\t')[1].cellFromBase64()
            assertThrows("Relabeled native body accepted: $version", Exception::class.java) {
                SignerRequestPolicy.requireSupported(body, version, 3, 7)
            }
        }
    }
    @Test fun nativeKeyRequiresStrictNativeProfileWhileLegacyOrdinaryRequestsRemainSupported() {
        val native = TosWalletV5R1Contract(key, 3).createTransferUnsignedBody(2000000000L, 7, false, null, gift)
        SignerRequestPolicy.requireSupported(native, "tosv5r1", 3, 7, nativeKey = true)
        for (version in listOf("v4r2", "v5r1", "unknown")) {
            assertThrows(Exception::class.java) { SignerRequestPolicy.requireSupported(native, version, 3, 7) }
        }
        for (version in listOf("v4r2", "v5r1")) {
            val legacy = BaseWalletContract.create(key, version, -239)
                .createTransferUnsignedBody(2000000000L, 7, false, null, gift)
            SignerRequestPolicy.requireSupported(legacy, version, -239, 7, nativeKey = false)
            assertThrows(Exception::class.java) {
                SignerRequestPolicy.requireSupported(legacy, version, -239, 7, nativeKey = true)
            }
        }
    }
}
