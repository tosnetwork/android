package network.tos.wallet.app.core.signer

import android.content.Context
import android.content.Intent
import android.net.Uri
import network.tos.blockchain.ton.extensions.hex
import network.tos.blockchain.ton.extensions.cellFromHex
import org.ton.api.pub.PublicKeyEd25519
import org.ton.boc.BagOfCells
import org.ton.cell.Cell
import org.ton.crypto.hex

object SignerApp {

    private const val STORE_LINK = "https://play.google.com/store/apps/details?id=network.tos.signer"

    fun openAppOrInstall(context: Context) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("tonsign://v1/"))
            open(context, intent)
        } catch (e: Throwable) {
            openStore(context)
        }
    }

    private fun openStore(context: Context) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(STORE_LINK))
        open(context, intent)
    }

    private fun open(context: Context, intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun createSignUri(cell: Cell, publicKey: PublicKeyEd25519): Uri {
        val boc = BagOfCells(cell)
        return createSignUri(boc, publicKey)
    }

    fun createSignUri(boc: BagOfCells, publicKey: PublicKeyEd25519): Uri {
        val body = hex(boc.toByteArray())
        return createSignUri(body, publicKey)
    }

    fun createSignUri(boc: String, publicKey: PublicKeyEd25519): Uri {
        val cell = boc.cellFromHex()
        val builder = Uri.parse("tonsign://v1/").buildUpon()
            .appendQueryParameter("pk", publicKey.hex()).appendQueryParameter("body", boc)
        if (cell.bits.size == 162) {
            val slice = cell.beginParse()
            slice.loadUInt(32)
            val networkId = slice.loadInt(32).toInt()
            slice.loadUInt(32)
            slice.loadUInt(32)
            val seqno = slice.loadUInt(32).toInt()
            network.tos.blockchain.ton.contract.TosV5SigningRequest.parse(cell, networkId, seqno)
            builder.appendQueryParameter("network", networkId.toString())
                .appendQueryParameter("v", "tosv5r1").appendQueryParameter("seqno", seqno.toString())
        } else {
            builder.appendQueryParameter("network", "mainnet")
        }
        return builder.build()
    }
}