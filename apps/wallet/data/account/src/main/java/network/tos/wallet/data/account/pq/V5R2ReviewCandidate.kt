package network.tos.wallet.data.account.pq

import android.content.Context
import network.tos.blockchain.ton.contract.V5R2Codes
import network.tos.blockchain.ton.contract.V5R2CodePins
import org.ton.boc.BagOfCells
import java.security.MessageDigest

/** Fixed development candidate only. No release/deployment/readiness approval or wallet default switch. */
object V5R2ReviewCandidate {
    const val minimumVm = 18
    const val globalId = 1
    const val configSha256 = "3d480a6c3d9eed12b6ea687ab75f9d0a5fbcbbbad780a003af6c17a1ec6f72c7"
    val network: ByteArray get() = ByteArray(32) { 0x42 }
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    fun load(context: Context): Pair<V5R2Codes, V5R2CodePins> {
        val walletBytes = context.assets.open("v5r2-review-candidate/wallet.boc").use { it.readBytes() }
        require(walletBytes.size == 3410 && MessageDigest.getInstance("SHA-256").digest(walletBytes).contentEquals(hex("96ba6f8b91ad961e9c2fd62be9fb6e662ebf77cc2cf72eb6c276f96e738ce4ec"))) { "Candidate wallet BOC mismatch" }
        val wallet = BagOfCells(walletBytes).roots.single()
        require(wallet.hash().toByteArray().contentEquals(hex("06203e98d4d8bf97b0cab37523ec435f5ab0e1d6d2c5253926eebf20ef1712f5"))) { "Candidate wallet code hash mismatch" }
        val moduleBytes = context.assets.open("v5r2-review-candidate/module.boc").use { it.readBytes() }
        require(moduleBytes.size == 3055 && MessageDigest.getInstance("SHA-256").digest(moduleBytes).contentEquals(hex("500dc4c115c636291a880c2d2874ab8c1c557dcaa5147421827b06824bba8475"))) { "Candidate module BOC mismatch" }
        val module = BagOfCells(moduleBytes).roots.single()
        require(module.hash().toByteArray().contentEquals(hex("f818dd311152d6ea13ef7f38002eb6f7390fac06bbe5df71bb8a2dd0c09c5b6d"))) { "Candidate module code hash mismatch" }
        val vaultBytes = context.assets.open("v5r2-review-candidate/vault.boc").use { it.readBytes() }
        require(vaultBytes.size == 690 && MessageDigest.getInstance("SHA-256").digest(vaultBytes).contentEquals(hex("0a8dd10e6450a3e469e86572fc52b49bfc64e10fe2cd61071747f4ba4922e38e"))) { "Candidate vault BOC mismatch" }
        val vault = BagOfCells(vaultBytes).roots.single()
        require(vault.hash().toByteArray().contentEquals(hex("9fa07637d49c4176766847251cfd820d5a11ade70fe92a45e11e419dba0452a0"))) { "Candidate vault code hash mismatch" }
        return V5R2Codes(wallet, module, vault) to V5R2CodePins(wallet.hash().toByteArray(), module.hash().toByteArray(), vault.hash().toByteArray())
    }
    fun repository(context: Context, authenticate: suspend () -> Boolean): V5R2WalletRepository {
        val (codes, pins) = load(context)
        return V5R2WalletRepository(context, codes, pins, authenticate, expectedNetwork = network, expectedGlobalId = globalId)
    }
}
