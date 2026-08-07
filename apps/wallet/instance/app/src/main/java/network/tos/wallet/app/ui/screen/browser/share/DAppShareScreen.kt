package network.tos.wallet.app.ui.screen.browser.share

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.core.net.toUri
import network.tos.extensions.getParcelableCompat
import network.tos.extensions.toUriOrNull
import network.tos.wallet.app.deeplink.DeepLinkBuilder
import network.tos.wallet.app.extensions.copyToClipboard
import network.tos.wallet.app.extensions.toast
import network.tos.wallet.app.ui.base.BaseWalletVM
import network.tos.wallet.app.ui.base.compose.ComposeWalletScreen
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.dapps.entities.AppEntity
import network.tos.wallet.localization.Localization
import org.koin.androidx.viewmodel.ext.android.viewModel
import uikit.base.BaseFragment

class DAppShareScreen(wallet: WalletEntity) : ComposeWalletScreen(wallet),
    BaseFragment.Modal {
    override val fragmentName: String = "DAppShareScreen"

    override val viewModel: BaseWalletVM.EmptyViewViewModel by viewModel()

    private val app: AppEntity by lazy {
        requireArguments().getParcelableCompat(ARG_APP)!!
    }

    private val appUrl: String by lazy {
        requireArguments().getString(ARG_URL)!!
    }

    private val deepLink: String by lazy {
        DeepLinkBuilder.dAppShare(appUrl)
    }

    private fun shareLink() {
        val sendIntent = Intent(Intent.ACTION_SEND)
        sendIntent.putExtra(Intent.EXTRA_TEXT, deepLink)
        sendIntent.type = "text/plain"
        val shareIntent = Intent.createChooser(sendIntent, null)
        startActivity(shareIntent)
    }

    private fun copyLink() {
        navigation?.toast(getString(Localization.copied))
        context?.copyToClipboard(deepLink)
    }

    @Composable
    override fun ScreenContent() {
        DAppShareComposable(
            url = deepLink.toUri(),
            icon = app.iconUrl.toUriOrNull(),
            name = app.name,
            onCopy = ::copyLink,
            onShare = ::shareLink,
            onFinishClick = { finish() }
        )
    }

    companion object {
        private const val ARG_APP = "app"
        private const val ARG_URL = "url"

        fun newInstance(wallet: WalletEntity, app: AppEntity, url: Uri): BaseFragment {
            val screen = DAppShareScreen(wallet)
            screen.putParcelableArg(ARG_APP, app)
            screen.putStringArg(ARG_URL, url.toString())
            return screen
        }
    }
}