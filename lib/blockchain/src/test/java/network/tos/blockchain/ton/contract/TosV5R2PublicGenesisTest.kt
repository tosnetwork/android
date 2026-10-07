package network.tos.blockchain.ton.contract

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.ton.boc.BagOfCells
import org.ton.cell.Cell
import java.io.ByteArrayOutputStream

/** SDK construction interoperability only; not deployed proof, custody or signing acceptance. */
class TosV5R2PublicGenesisTest {
    private fun bytes(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun chain(root: Cell): ByteArray {
        val out = ByteArrayOutputStream()
        var cell = root
        while (true) {
            val slice = cell.beginParse()
            require(slice.remainingBits % 8 == 0 && cell.refs.size <= 1)
            out.write(slice.loadBits(slice.remainingBits).toByteArray())
            if (cell.refs.isEmpty()) return out.toByteArray()
            cell = cell.refs.single()
        }
    }
    @Test fun mobileConstructionMatchesNativeSdkGenesisCells() {
        val text = checkNotNull(javaClass.classLoader!!.getResourceAsStream("tos-v5r2-public-genesis-accounts.json"))
            .bufferedReader().use { it.readText() }
        val fixture = Json.parseToJsonElement(text).jsonObject
        val input = fixture.getValue("input").jsonObject
        val output = fixture.getValue("output").jsonObject
        fun cell(source: JsonObject, name: String) = BagOfCells(bytes(source.getValue(name).jsonPrimitive.content)).roots.single()
        val codes = V5R2Codes(cell(input,"wallet_code"), cell(input,"module_code"), cell(input,"vault_code"))
        val module = cell(output,"module_data").beginParse()
        assertEquals(1, module.loadUInt(8).toInt())
        val global = module.loadInt(32).toInt()
        val network = module.loadBits(256).toByteArray()
        assertEquals(1, module.loadUInt(8).toInt())
        val primary = chain(module.loadRef())
        val rescue = module.loadBits(256).toByteArray()
        val policy = module.loadUInt(8).toInt()
        val wd = cell(output,"wallet_data")
        val wallet = wd.beginParse(); wallet.loadBits(33)
        val walletId = wallet.loadUInt(32).toLong()
        val metadata = wd.refs.single().refs[1].beginParse()
        assertEquals(1, metadata.loadUInt(8).toInt()); assertEquals(1, metadata.loadUInt(8).toInt())
        val tree = metadata.loadBits(256).toByteArray()
        val epoch0 = metadata.loadUInt(32).toLong()
        val fee = chain(metadata.loadRef())
        val pins = V5R2CodePins(codes.wallet.hash().toByteArray(), codes.module.hash().toByteArray(), codes.vault.hash().toByteArray())
        val birth = TosV5R2Genesis(codes,pins,global,network,walletId,primary,rescue,
            if (policy==1) V5R2Policy.READY else V5R2Policy.REQUIRED,tree,fee,epoch0)
        for ((name,actual) in mapOf("wallet_init" to birth.walletInit,"wallet_data" to birth.walletData,
            "module_init" to birth.moduleInit,"module_data" to birth.moduleData,
            "vault_init" to birth.vaultInit,"vault_data" to birth.vaultData))
            assertEquals("Mobile/native SDK identity mismatch: $name",cell(output,name).hash(),actual.hash())
        assertEquals(1uL,TosV5R2WalletData.parse(wd,birth).epoch)
    }
}
