package network.tos.blockchain.ton.contract

import java.math.BigInteger
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test
import org.ton.block.AddrStd
import org.ton.cell.Cell
import org.ton.cell.buildCell
import org.ton.crypto.hex

class TosQuantumFeeTest {
    private fun hash(n: Int) = ByteArray(32).also { it[31] = n.toByte() }
    private fun byte(n: Int) = buildCell { storeUInt(n,8) }
    private fun binding() = QuantumRecoveryBinding(42,hash(123),AddrStd(0,hash(100)),AddrStd(0,hash(101)),1780000600)
    private fun payload(kind: QuantumFeeClass, role: QuantumAuthRole = QuantumAuthRole.RESCUE): Cell {
        val signature=ByteArray(role.signatureSize) { 0xa5.toByte() }
        return when(kind) {
            QuantumFeeClass.RESCUE_AUTH -> TosQuantumAuth(42,hash(123),AddrStd(0,hash(100)),AddrStd(0,hash(101)),role,1u,0u,1780000600,QuantumAuthAction.Execute(Cell.empty()),1780000000).submission(signature)
            QuantumFeeClass.POP -> TosQuantumPop(binding(),role,QuantumPolicy.REQUIRED,hash(111),hash(222),hash(99),1780000000).submission(signature)
            QuantumFeeClass.PREPARE -> TosQuantumPreparation(binding(),BigInteger("10000000000"),BigInteger("20000000000"),byte(1),byte(2),byte(3),1780000000).submission(signature)
        }
    }
    private fun fee(kind: QuantumFeeClass = QuantumFeeClass.RESCUE_AUTH, leaf: Int = 8, value: BigInteger = BigInteger("5000000000"), p: Cell = payload(kind)) =
        TosQuantumFee(AddrStd(0,hash(103)),hash(104),1779992790,leaf,1780000600,value,kind,p,1780000000)
    private fun signature() = ByteArray(2832) { 0xa5.toByte() }.also { val b=ByteBuffer.wrap(it); b.putInt(0,0);b.putInt(4,8);b.putInt(8,3);b.putInt(2188,8) }
    private val vectors = listOf(
        mapOf("kind" to "1", "value" to "5000000000", "intent_hash" to "e1d2c40975d7b824f33062d1a166e453de7a84f541a97c228e000ec18cc9c075", "external_hash" to "05a8cd77f02a4b55ab53882b162eab8270d4a98005be40a18405bce8bcf71d27"),
        mapOf("kind" to "2", "value" to "5000000000", "intent_hash" to "8f0e0a5b54bf5f997a3a24cfbfeb390c0589fce720a16710530cbcbdf8b4ec22", "external_hash" to "1726c16e2c56e2f2c8ba20d82396cff26bca6a4c8d2707a8de6602a106ee81b0"),
        mapOf("kind" to "3", "value" to "50000000000", "intent_hash" to "251b8014846d093933d0ae64aee6ee21b372980eb5244602a99f45fde98e103b", "external_hash" to "7df780a59d41753d319f2169991cfe07ac54423ae9cfd01e2dcd9ecbf7e637a2"),
        mapOf("kind" to "1", "value" to "1329227995784915872903807060280344575", "intent_hash" to "f6b8569c230c5cee555017eb6cfa4e43b172610f47922e961678f5736f4e9548", "external_hash" to "97d97ab60ff1249bea87570bf6bf524a53649c772aa9f5e9e7f40aafc4c8aa20")
    )
    @Test fun allIndependentIntentAndExternalVectors() {
        for(v in vectors) {
            val kind=QuantumFeeClass.entries.first { it.id == v.getValue("kind").toInt() }
            val r=fee(kind,value=BigInteger(v.getValue("value")))
            assertArrayEquals(hex(v.getValue("intent_hash")),r.digest)
            assertArrayEquals(hex(v.getValue("external_hash")),r.external(signature()).hash().toByteArray())
            var chain=r.external(signature()).refs[1];var count=1
            while(chain.refs.isNotEmpty()) { assertEquals(1016,chain.bits.size);chain=chain.refs[0];count+=1 }
            assertEquals(23,count);assertEquals(304,chain.bits.size)
        }
    }
    @Test fun primaryCannotUseFeeAuthAndClassesCannotBeAliased() {
        assertEquals("Fee vault cannot fund PRIMARY AUTH",runCatching { fee(p=payload(QuantumFeeClass.RESCUE_AUTH,QuantumAuthRole.PRIMARY)) }.exceptionOrNull()?.message)
        for(kind in QuantumFeeClass.entries)for(other in QuantumFeeClass.entries)if(kind!=other)
            assertEquals("Fee class and submission mismatch",runCatching { fee(kind,p=payload(other)) }.exceptionOrNull()?.message)
        fee(QuantumFeeClass.POP,p=payload(QuantumFeeClass.POP,QuantumAuthRole.PRIMARY))
    }
    @Test fun slotTerminalLeafSignatureProfileAndAmountsRefused() {
        for(leaf in listOf(4,7,12))assertEquals("New fee signature requires current slot",runCatching { fee(leaf=leaf) }.exceptionOrNull()?.message)
        for(leaf in listOf(-1,1 shl 20))assertEquals("Fee tree exhausted or invalid leaf",runCatching { fee(leaf=leaf) }.exceptionOrNull()?.message)
        for(value in listOf(BigInteger.ZERO,BigInteger.ONE.shiftLeft(120)))assertEquals("Positive canonical fee Coins required",runCatching { fee(value=value) }.exceptionOrNull()?.message)
        val r=fee()
        for(offset in listOf(0,4,8,2188)) { val sig=signature();ByteBuffer.wrap(sig).putInt(offset,99);assertEquals("Fee signature profile or leaf mismatch",runCatching { r.external(sig) }.exceptionOrNull()?.message) }
        for(size in listOf(64,2831,2833))assertEquals("Fee signature length",runCatching { r.external(ByteArray(size)) }.exceptionOrNull()?.message)
    }
}
