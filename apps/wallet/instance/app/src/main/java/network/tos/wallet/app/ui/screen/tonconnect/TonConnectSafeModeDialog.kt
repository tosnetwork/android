package network.tos.wallet.app.ui.screen.tonconnect

import android.content.Context
import android.view.View
import network.tos.wallet.app.ui.screen.settings.security.SecurityScreen
import network.tos.wallet.app.R
import network.tos.wallet.data.account.entities.WalletEntity
import uikit.dialog.modal.ModalDialog
import uikit.navigation.Navigation
import uikit.widget.ModalHeader

class TonConnectSafeModeDialog(context: Context): ModalDialog(context, R.layout.dialog_tonconnect_safemode) {

    private val navigation: Navigation? by lazy {
        Navigation.from(context)
    }

    init {
        findViewById<ModalHeader>(R.id.header)!!.onCloseClick = { dismiss() }

        findViewById<View>(R.id.cancel)!!.setOnClickListener {
            dismiss()
        }
    }


    fun show(wallet: WalletEntity) {
        super.show()
        findViewById<View>(R.id.open_settings)!!.setOnClickListener {
            dismiss()
            navigation?.add(SecurityScreen.newInstance(wallet))
        }
    }

}