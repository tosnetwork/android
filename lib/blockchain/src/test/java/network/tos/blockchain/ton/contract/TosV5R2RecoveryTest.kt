package network.tos.blockchain.ton.contract

import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Test
import org.ton.block.AddrStd
import org.ton.cell.buildCell
import org.ton.crypto.hex

class TosV5R2RecoveryTest {
    private fun hash(n: Int) = ByteArray(32).also { it[31] = n.toByte() }
    private fun binding(deadline: Long = 1780000600) = V5R2RecoveryBinding(42, hash(123), AddrStd(0, hash(100)), AddrStd(0, hash(101)), deadline)
    private fun byte(n: Int) = buildCell { storeUInt(n, 8) }
    private val pops = listOf(
        mapOf("role" to "1", "policy" to "1", "request_hash" to "e682e38a1b36f00d9966585725ea68ca07f329cd74a7a76c05d7dec6ce796a8c", "digest" to "630841d36514c55c64864274186ff52e5bfa2037e6595d7bc1424637b37f68e7", "submission_hash" to "c5ff27d2b8a7c38512a3a9a51eb51a6513334a449f923700a2ad64b8ae8272c6"),
        mapOf("role" to "1", "policy" to "2", "request_hash" to "7bffd24f044a0d62937d40460395e831b9727fa616296d28f11122f61d8cc830", "digest" to "ff1ec79c0992df5aaf5cee15b3a9eaaf78846a68e2ee3c73bb55f60a335695cb", "submission_hash" to "ca2a338a44237aae86214ed782cc5e177fac82a7f89876fd0ec296fb07f71dcc"),
        mapOf("role" to "2", "policy" to "1", "request_hash" to "a22770697e7487a6422418fc91d2483fe6959f60b6a4d37fc2f65b3583c87db2", "digest" to "f2b831b48815d699990607b21539e8ed34550ec530d4743edb620da61fd0e1ae", "submission_hash" to "a3ffad86b32d1db2b30ed068f1b8c4b283ab9783ed6685510531daeef181bcbc"),
        mapOf("role" to "2", "policy" to "2", "request_hash" to "9277bdb7d71f9b72b55f09ac32c982c0d9c089d242a8fb9d4439b12bd363ad8a", "digest" to "368b1dc85f92b8968b825a7e21da3a332e54aeaa5ad1141efbab13406445519b", "submission_hash" to "c204539096696abb9a34c3505434cd38a9e80d2246e92d609a92c9868e306d30")
    )
    private val preparations = listOf(
        mapOf("module_amount" to "1", "vault_amount" to "1", "request_hash" to "992ccaa3160ca76650fa48916cca1de122dac22f6dec2bebdd5c9b8cef5944ff", "submission_hash" to "f1c71045b99ae68b41eed8137328ea6b8381c522731b6b175d0fa278774f5d38"),
        mapOf("module_amount" to "10000000000", "vault_amount" to "20000000000", "request_hash" to "0e84ef7c41cf1b85350867fb17a12540c3a027beacbdd08ec5f9f9cd0c47b4c8", "submission_hash" to "9769899470ce42de9da8b53a5f39a418c2d7d8c7e3199cde90fe6b50cbf15be4"),
        mapOf("module_amount" to "18446744073709551616", "vault_amount" to "1329227995784915872903807060280344575", "request_hash" to "00f6d7d00a6f84e894ce8bd0a2f25df244b29ab28bbfbfdd32f970e170c80971", "submission_hash" to "67a7a7f77fef9f95b4c2394a2a67a1246b8a37c61923d519f94d9b0398c5be44")
    )
    @Test fun allIndependentPopRolesPoliciesAndSubmissions() {
        for (v in pops) {
            val role = if (v.getValue("role") == "1") V5R2AuthRole.PRIMARY else V5R2AuthRole.RESCUE
            val policy = if (v.getValue("policy") == "1") V5R2Policy.READY else V5R2Policy.REQUIRED
            val r = TosV5R2Pop(binding(), role, policy, hash(111), hash(222), hash(99), 1780000000)
            assertArrayEquals(hex(v.getValue("request_hash")), r.request.hash().toByteArray())
            assertArrayEquals(hex(v.getValue("digest")), r.digest)
            assertArrayEquals(hex(v.getValue("submission_hash")), r.submission(ByteArray(role.signatureSize) { 0xa5.toByte() }).hash().toByteArray())
        }
    }
    @Test fun allIndependentPreparationAmountsAndSubmissions() {
        for (v in preparations) {
            val a = BigInteger(v.getValue("module_amount")); val b = BigInteger(v.getValue("vault_amount"))
            val r = TosV5R2Preparation(binding(), a, b, byte(1), byte(2), byte(3), 1780000000)
            assertArrayEquals(hex(v.getValue("request_hash")), r.digest)
            assertEquals(a.add(b), r.deploymentValue)
            assertArrayEquals(hex(v.getValue("submission_hash")), r.submission(ByteArray(7856) { 0xa5.toByte() }).hash().toByteArray())
        }
    }
    @Test fun malformedChallengeAmountDeadlineAndSignatureRefused() {
        for (c in listOf(ByteArray(32), ByteArray(31) { 1 }, ByteArray(33) { 1 }))
            assertEquals("Nonzero 32-byte POP challenge required", runCatching { TosV5R2Pop(binding(), V5R2AuthRole.PRIMARY, V5R2Policy.READY, hash(111), hash(222), c, 1780000000) }.exceptionOrNull()?.message)
        for (a in listOf(BigInteger.ZERO, BigInteger.valueOf(-1)))
            assertEquals("Positive deployment amounts required", runCatching { TosV5R2Preparation(binding(), a, BigInteger.ONE, byte(1), byte(2), byte(3), 1780000000) }.exceptionOrNull()?.message)
        assertEquals("Deployment Coins overflow", runCatching { TosV5R2Preparation(binding(), BigInteger.ONE.shiftLeft(120), BigInteger.ONE, byte(1), byte(2), byte(3), 1780000000) }.exceptionOrNull()?.message)
        for (deadline in listOf(1780000000L,1780003601L)) assertEquals("Recovery TTL must be 1..3600", runCatching { TosV5R2Pop(binding(deadline), V5R2AuthRole.RESCUE, V5R2Policy.READY, hash(111), hash(222), hash(99), 1780000000) }.exceptionOrNull()?.message)
        val r = TosV5R2Preparation(binding(), BigInteger.ONE, BigInteger.ONE, byte(1), byte(2), byte(3), 1780000000)
        for (length in listOf(64,2420,7855,7857)) assertEquals("SLH preparation signature required", runCatching { r.submission(ByteArray(length)) }.exceptionOrNull()?.message)
    }
}
