package network.tos.wallet.app.ui.screen.browser.confirm

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.core.net.toUri
import network.tos.extensions.getParcelableCompat
import network.tos.wallet.app.extensions.copyToClipboard
import network.tos.wallet.app.extensions.toast
import network.tos.wallet.app.koin.settingsRepository
import network.tos.wallet.app.ui.base.BaseWalletVM
import network.tos.wallet.app.ui.base.compose.ComposeWalletScreen
import network.tos.wallet.app.ui.screen.browser.dapp.DAppScreen
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.dapps.entities.AppEntity
import network.tos.wallet.localization.Localization
import org.koin.androidx.viewmodel.ext.android.viewModel
import uikit.base.BaseFragment

class DAppConfirmScreen(wallet: WalletEntity) : ComposeWalletScreen(wallet),
    BaseFragment.Modal {
    override val fragmentName: String = "DAppShareScreen"

    override val viewModel: BaseWalletVM.EmptyViewViewModel by viewModel()

    private val app: AppEntity
        get() = arguments?.getParcelableCompat(ARG_APP)!!

    private val dAppUrl: Uri
        get() = arguments?.getString(ARG_URL)!!.toUri()

    private fun openDApp() {
        navigation?.add(DAppScreen.newInstance(
            wallet = wallet,
            title = app.name,
            url = dAppUrl,
            iconUrl = app.iconUrl,
            source = "deep-link",
        ))
        finish()
    }

    @Composable
    override fun ScreenContent() {
        DAppConfirmComposable(
            host = dAppUrl.host ?: app.host,
            icon = app.iconUrl.toUri(),
            name = app.name,
            onOpen = ::openDApp,
            onCheckedChange = { checked ->
                context?.settingsRepository?.setDAppOpenConfirm(wallet.id, app.host, !checked)
            },
            onFinishClick = { finish() }
        )
    }

    companion object {
        private const val ARG_APP = "app"
        private const val ARG_URL = "url"

        fun newInstance(wallet: WalletEntity, app: AppEntity, url: Uri): BaseFragment {
            val screen = DAppConfirmScreen(wallet)
            screen.putParcelableArg(ARG_APP, app)
            screen.putStringArg(ARG_URL, url.toString())
            return screen
        }
    }
}