package network.tos.wallet.app.ui.component

import android.content.Context
import android.net.Uri
import android.util.AttributeSet
import android.util.Log
import network.tos.extensions.toUriOrNull
import uikit.widget.webview.bridge.BridgeWebView

class TonConnectWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = android.R.attr.webViewStyle,
) : BridgeWebView(context, attrs, defStyle) {

    val uri: Uri?
        get() = url?.toUriOrNull()

    init {
        isVerticalScrollBarEnabled = false
        settings.setSupportZoom(false)
    }
}