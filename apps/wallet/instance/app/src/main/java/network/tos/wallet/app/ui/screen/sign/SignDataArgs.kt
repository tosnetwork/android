package network.tos.wallet.app.ui.screen.sign

import android.net.Uri
import android.os.Bundle
import network.tos.extensions.getParcelableCompat
import network.tos.wallet.app.manager.tonconnect.bridge.model.SignDataRequestPayload
import network.tos.wallet.data.dapps.entities.AppEntity
import uikit.base.BaseArgs

data class SignDataArgs(
    val appUrl: Uri,
    val payload: SignDataRequestPayload,
): BaseArgs() {

    private companion object {
        private const val ARG_APP = "app"
        private const val ARG_PAYLOAD = "payload"
    }

    constructor(bundle: Bundle) : this(
        appUrl = bundle.getParcelableCompat(ARG_APP)!!,
        payload = bundle.getParcelableCompat(ARG_PAYLOAD)!!
    )

    override fun toBundle(): Bundle {
        val bundle = Bundle()
        bundle.putParcelable(ARG_APP, appUrl)
        bundle.putParcelable(ARG_PAYLOAD, payload)
        return bundle
    }
}