package network.tos.signer.screen.sign

import android.os.Bundle
import network.tos.blockchain.ton.TonNetwork
import network.tos.blockchain.ton.extensions.cellFromHex
import network.tos.blockchain.ton.extensions.hex
import network.tos.signer.Key
import network.tos.signer.deeplink.entities.ReturnResultEntity
import network.tos.signer.extensions.getEnum
import network.tos.signer.extensions.getObject
import network.tos.signer.extensions.putEnum
import org.ton.cell.Cell

data class SignArgs(private val args: Bundle) {

    companion object {

        fun bundle(
            id: Long,
            body: Cell,
            v: String,
            returnResult: ReturnResultEntity,
            seqno: Int,
            network: TonNetwork,
        ) = Bundle().apply {
            putLong(Key.ID, id)
            putString(Key.V, v)
            putString(Key.BODY, body.hex())
            putParcelable(Key.RETURN, returnResult)
            putInt(Key.SEQNO, seqno)
            putEnum(Key.NETWORK, network)
        }
    }

    val id = args.getLong(Key.ID)
    val body: Cell = args.getString(Key.BODY)!!.cellFromHex()
    val v: String = args.getString(Key.V)!!
    val returnResult = args.getObject<ReturnResultEntity>(Key.RETURN)
    val seqno = args.getInt(Key.SEQNO)
    val network = args.getEnum(Key.NETWORK, TonNetwork.MAINNET)

    val bodyHex: String by lazy { body.hex() }
}
