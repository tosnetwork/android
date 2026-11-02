package network.tos.wallet.data.account.pq

import android.content.Context
import network.tos.blockchain.ton.contract.QuantumCodes
import network.tos.blockchain.ton.contract.QuantumCodePins
import org.ton.boc.BagOfCells
import java.security.MessageDigest

/** Fixed development candidate only. No release/deployment/readiness approval or wallet default switch. */
object QuantumReviewCandidate {
    const val minimumVm = 16
    const val globalId = 1
    const val configSha256 = "59880ee43f99c6e103bb60a99af4696bbadaff0dc7c064c04b438cc0a904d800"
    val network: ByteArray get() = ByteArray(32) { 0x42 }
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    fun load(context: Context): Pair<QuantumCodes, QuantumCodePins> {
        val walletBytes = context.assets.open("quantum-review-candidate/wallet.boc").use { it.readBytes() }
        require(walletBytes.size == 3446 && MessageDigest.getInstance("SHA-256").digest(walletBytes).contentEquals(hex("ca804d2c2162b79ec47691da2889e991b6001c90336226f37967a3aca6ca3089"))) { "Candidate wallet BOC mismatch" }
        val wallet = BagOfCells(walletBytes).roots.single()
        require(wallet.hash().toByteArray().contentEquals(hex("18252490f90f12149832b6067ca60f3f0216f23a97d71e7afcd8017eeb75e61f"))) { "Candidate wallet code hash mismatch" }
        val moduleBytes = context.assets.open("quantum-review-candidate/module.boc").use { it.readBytes() }
        require(moduleBytes.size == 3055 && MessageDigest.getInstance("SHA-256").digest(moduleBytes).contentEquals(hex("500dc4c115c636291a880c2d2874ab8c1c557dcaa5147421827b06824bba8475"))) { "Candidate module BOC mismatch" }
        val module = BagOfCells(moduleBytes).roots.single()
        require(module.hash().toByteArray().contentEquals(hex("f818dd311152d6ea13ef7f38002eb6f7390fac06bbe5df71bb8a2dd0c09c5b6d"))) { "Candidate module code hash mismatch" }
        val vaultBytes = context.assets.open("quantum-review-candidate/vault.boc").use { it.readBytes() }
        require(vaultBytes.size == 690 && MessageDigest.getInstance("SHA-256").digest(vaultBytes).contentEquals(hex("0a8dd10e6450a3e469e86572fc52b49bfc64e10fe2cd61071747f4ba4922e38e"))) { "Candidate vault BOC mismatch" }
        val vault = BagOfCells(vaultBytes).roots.single()
        require(vault.hash().toByteArray().contentEquals(hex("9fa07637d49c4176766847251cfd820d5a11ade70fe92a45e11e419dba0452a0"))) { "Candidate vault code hash mismatch" }
        return QuantumCodes(wallet, module, vault) to QuantumCodePins(wallet.hash().toByteArray(), module.hash().toByteArray(), vault.hash().toByteArray())
    }
    fun repository(context: Context, authenticate: suspend () -> Boolean): QuantumWalletRepository {
        val (codes, pins) = load(context)
        return QuantumWalletRepository(context, codes, pins, authenticate, expectedNetwork = network, expectedGlobalId = globalId)
    }
}
