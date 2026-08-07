package network.tos.signer.deeplink.entities

import android.net.Uri
import network.tos.blockchain.ton.TonNetwork
import network.tos.blockchain.ton.extensions.cellFromHex
import network.tos.blockchain.ton.extensions.publicKeyFromHex
import network.tos.extensions.getMultipleQuery
import network.tos.signer.Key
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

    private val tonNetwork: String = uri.getMultipleQuery("tn", "network") ?: "mainnet"

    val body: Cell = uri.getQueryParameter(Key.BODY)?.cellFromHex() ?: throw IllegalArgumentException("body is required")
    val publicKey: PublicKeyEd25519 = uri.getQueryParameter(Key.PK)?.publicKeyFromHex() ?: throw IllegalArgumentException("pk is required")

    val network: TonNetwork = if (tonNetwork == "mainnet" || tonNetwork == "-239") {
        TonNetwork.MAINNET
    } else {
        TonNetwork.TESTNET
    }

    val v: String = uri.getQueryParameter(Key.V) ?: "v4r2"
    val seqno: Int = uri.getQueryParameter(Key.SEQNO)?.toIntOrNull() ?: 1


}