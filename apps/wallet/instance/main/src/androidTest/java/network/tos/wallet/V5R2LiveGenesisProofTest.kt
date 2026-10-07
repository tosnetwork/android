package network.tos.wallet

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import network.tos.blockchain.ton.contract.*
import network.tos.security.pq.*
import network.tos.wallet.data.account.pq.V5R2InstalledWallet
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.ton.boc.BagOfCells
import org.ton.cell.Cell
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Real captured proofs, controlled fixture clock; no current network or signing acceptance. */
@RunWith(AndroidJUnit4::class)
class V5R2LiveGenesisProofTest {
    private fun bytes(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun chain(root: Cell): ByteArray {
        val out = ByteArrayOutputStream(); var cell = root
        while (true) {
            val s=cell.beginParse(); require(s.remainingBits%8==0 && cell.refs.size<=1)
            out.write(s.loadBits(s.remainingBits).toByteArray())
            if(cell.refs.isEmpty()) return out.toByteArray()
            cell=cell.refs.single()
        }
    }
    @Test fun nativeProofsBindActualGenesisTuple() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val assets=instrumentation.context.assets
        fun read(name:String)=assets.open("v5r2-live-genesis/$name").use{it.readBytes()}
        val text=read("public-genesis-accounts.json").toString(Charsets.UTF_8)
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
        val now=JSONObject(read("manifest.json").toString(Charsets.UTF_8)).getLong("controlled_now")
        val directories=mutableListOf<File>()
        fun prove(role:String): V5R2VerifiedRead {
            val id=UUID.randomUUID();directories.add(File(context.noBackupFilesDir,"v5r2-proof-checkpoints/$id"))
            val session=V5R2ProofSession(context,id,read("anchor.json"))
            val names=if(role=="policy") listOf("masterchain-info.tl","chain-0000.tl","config.tl","account.tl") else listOf("chain-0000.tl","account.tl")
            val replies=names.map{read("$role/material/$it")}
            var calls=0
            val transport=V5R2ProofTransport { _,capacity -> replies[calls++].also{require(it.size<=capacity)} }
            val proven=session.enrollBound(read("$role/request.json"),now,transport)
            assertEquals("Unexpected proof acquisition sequence",names.size,calls)
            return proven
        }
        try {
            val walletProof=prove("wallet"); val moduleProof=prove("module"); val vaultProof=prove("vault")
            val installed=V5R2InstalledWallet.bindInitial(birth,walletProof,moduleProof,vaultProof,now,300)
            assertEquals(1uL,installed.state.epoch)
            assertEquals(0L,installed.nextFeeLeaf)
            assertEquals("1000000000000000",installed.walletAccount.balance.toString())
            assertEquals("1000000000000",installed.moduleAccount.balance.toString())
            installed.requireFeeProof(now,300)
            val policyProof=prove("policy")
            installed.requirePrimaryExecution(policyProof,now,300)
            installed.requirePrimaryCustody(primary,policyProof,now,300)
            val request=installed.primaryExecuteRequest(policyProof,org.ton.cell.buildCell { },now+60,now,300)
            assertEquals(V5R2AuthRole.PRIMARY,request.role)
            assertEquals(32,request.digest.size)
            val badKey=primary.copyOf().also { it[0]=(it[0].toInt() xor 1).toByte() }
            val keyError=runCatching { installed.requirePrimaryCustody(badKey,policyProof,now,300) }.exceptionOrNull()
            assertEquals("Wrong current PRIMARY key accepted", "Primary custody key differs from module enrollment",keyError?.message)
            val expired=runCatching { installed.primaryExecuteRequest(policyProof,org.ton.cell.buildCell { },now,now,300) }.exceptionOrNull()
            assertEquals("Expired PRIMARY request accepted", "Primary deadline expired by local clock",expired?.message)


            val wrongNetwork=network.copyOf().also { it[0]=(it[0].toInt() xor 1).toByte() }
            val wrongBirth=TosV5R2Genesis(codes,pins,global,wrongNetwork,walletId,primary,rescue,
                if(policy==1) V5R2Policy.READY else V5R2Policy.REQUIRED,tree,fee,epoch0)
            val wrong=runCatching { V5R2InstalledWallet.bindInitial(wrongBirth,walletProof,moduleProof,vaultProof,now,300) }.exceptionOrNull()
            assertEquals("Wrong enrollment accepted", "Proof account binding refused", wrong?.message)
            val staleProof=runCatching { walletProof.requireLive(now+301,300) }.exceptionOrNull()
            assertEquals("Expired proof passed the freshness gate", "Proof freshness refused", staleProof?.message)
            val stale=runCatching { installed.requireFeeProof(now+301,300) }.exceptionOrNull()
            assertEquals("Stale captured state accepted", "Proof freshness refused", stale?.message)

        } finally { directories.forEach{it.deleteRecursively()} }
    }
}
