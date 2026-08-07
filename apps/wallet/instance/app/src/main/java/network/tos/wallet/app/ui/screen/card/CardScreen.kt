package network.tos.wallet.app.ui.screen.card

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import network.tos.extensions.appVersionName
import network.tos.extensions.bestMessage
import network.tos.extensions.toUriOrNull
import network.tos.wallet.app.koin.walletViewModel
import network.tos.wallet.app.manager.tonconnect.ConnectRequest
import network.tos.wallet.app.manager.tonconnect.TonConnect
import network.tos.wallet.app.manager.tonconnect.TonConnectManager
import network.tos.wallet.app.manager.tonconnect.bridge.BridgeException
import network.tos.wallet.app.manager.tonconnect.bridge.JsonBuilder
import network.tos.wallet.app.manager.tonconnect.bridge.model.BridgeError
import network.tos.wallet.app.manager.tonconnect.bridge.model.BridgeEvent
import network.tos.wallet.app.manager.tonconnect.bridge.model.BridgeMethod
import network.tos.wallet.app.ui.base.InjectedTonConnectScreen
import network.tos.wallet.app.ui.base.WalletContextScreen
import network.tos.wallet.app.ui.component.TonConnectWebView
import network.tos.wallet.app.ui.screen.send.transaction.SendTransactionScreen
import network.tos.wallet.app.R
import network.tos.wallet.api.API
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.core.entity.SignRequestEntity
import org.json.JSONArray
import org.json.JSONObject
import org.koin.android.ext.android.inject
import uikit.base.BaseFragment
import uikit.extensions.activity
import uikit.widget.webview.WebViewFixed
import java.util.concurrent.CancellationException

class CardScreen(wallet: WalletEntity): InjectedTonConnectScreen(R.layout.fragment_card, wallet), BaseFragment.SwipeBack {

    override val fragmentName: String = "CardScreen"

    override val viewModel: CardViewModel by walletViewModel()

    override lateinit var webView: TonConnectWebView

    override val startUri: Uri
        get() = viewModel.url

    private val webViewCallback = object : WebViewFixed.Callback() {
        override fun shouldOverrideUrlLoading(request: WebResourceRequest): Boolean {
            return overrideUrlLoading(request)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        webView = view.findViewById(R.id.webView)
        webView.settings.useWideViewPort = true
        webView.settings.loadWithOverviewMode = true
        webView.addCallback(webViewCallback)
        webView.jsBridge = CardBridge(
            deviceInfo = deviceInfo.toString(),
            send = ::tonconnectSend,
            connect = ::tonconnect,
            restoreConnection = { viewModel.restoreConnection(webView.url?.toUriOrNull()) },
            disconnect = { viewModel.disconnect() },
            tonapiFetch = ::tonapiFetch,
        )
        webView.loadUrl(viewModel.url.toString())

        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val statusInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val bottomInsets = insets.getInsets(WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.navigationBars())
            applyWebViewOffset(statusInsets.top, bottomInsets.bottom)
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        webView.addCallback(webViewCallback)
    }

    override fun onPause() {
        super.onPause()
        webView.removeCallback(webViewCallback)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        webView.removeCallback(webViewCallback)
        webView.destroy()
    }

    private fun applyWebViewOffset(top: Int, bottom: Int) {
        webView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = top
            bottomMargin = bottom
        }
    }

    companion object {

        fun newInstance(wallet: WalletEntity) = CardScreen(wallet)
    }
}