package network.tos.wallet.app.extensions

import android.net.Uri
import androidx.core.net.toUri
import network.tos.wallet.app.ui.screen.root.RootViewModel

fun RootViewModel.routeToHistoryTab(from: String) {
    val uri = "tos://history?from=$from".toUri()
    processDeepLink(uri, false, Uri.EMPTY, false, context.packageName)
}