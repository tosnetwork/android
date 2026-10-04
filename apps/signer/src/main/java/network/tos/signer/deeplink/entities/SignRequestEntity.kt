package network.tos.signer.deeplink.entities

import android.net.Uri
import network.tos.blockchain.ton.contract.SigningNetworkId
import network.tos.blockchain.ton.contract.TosV5SigningRequest
import network.tos.blockchain.ton.extensions.cellFromHex
import network.tos.blockchain.ton.extensions.publicKeyFromHex
import network.tos.extensions.getMultipleQuery
import network.tos.signer.Key
import network.tos.signer.screen.sign.SignerRequestPolicy
import org.ton.api.pub.PublicKeyEd25519
import org.ton.cell.Cell

data class SignRequestEntity(
    val uri: Uri,
    val returnResult: ReturnResultEntity
) {

    companion object {

        fun safe(uri: Uri, returnResult: ReturnResultEntity): SignRequestEntity? {
            return try {
                SignRequestEntity(uri, returnResult)
            } catch (e: Throwable) {
                null
            }
        }
    }

    val body: Cell = uri.getQueryParameter(Key.BODY)?.cellFromHex() ?: throw IllegalArgumentException("body is required")
    val publicKey: PublicKeyEd25519 = uri.getQueryParameter(Key.PK)?.publicKeyFromHex() ?: throw IllegalArgumentException("pk is required")
    val v: String = uri.getQueryParameter(Key.V) ?: "v4r2"
    private val tosV5 = v.equals("tosv5r1", ignoreCase = true)
    val network: Int = SigningNetworkId.parse(
        uri.getMultipleQuery("tn", "network"), requireExplicit = tosV5,
    )
    val seqno: Int = uri.getQueryParameter(Key.SEQNO)?.toIntOrNull()
        ?: if (tosV5) error("TOS V5 seqno is required") else 1

    init {
        SignerRequestPolicy.requireSupported(body, v, network, seqno)
    }
}
