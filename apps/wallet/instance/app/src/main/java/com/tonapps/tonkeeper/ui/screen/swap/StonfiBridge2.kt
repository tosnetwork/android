package com.tonapps.tonkeeper.ui.screen.swap

import android.net.Uri
import android.util.Log
import com.tonapps.wallet.data.core.entity.SignRequestEntity
import org.json.JSONArray
import uikit.widget.webview.bridge.JsBridge

class StonfiBridge2(
    val address: String,
    val origin: Uri,
    val close: () -> Unit,
    val sendTransaction: suspend (request: SignRequestEntity) -> String?
): JsBridge("tonkeeperStonfi") {

    override val availableFunctions = arrayOf("close", "sendTransaction")

    init {
        keys["address"] = address
    }

    override suspend fun invokeFunction(name: String, args: JSONArray): Any? {
        if (name == "close") {
            close()
            return null
        } else if (name == "sendTransaction" && args.length() == 1) {
            // Use the real provider origin (the WebView is locked to this host) rather than
            // a hardcoded value, so the confirmation reflects who actually requested it.
            val request = SignRequestEntity(args.getJSONObject(0), origin)
            return sendTransaction(request)
        }
        throw IllegalArgumentException("Unknown function: $name")
    }

}