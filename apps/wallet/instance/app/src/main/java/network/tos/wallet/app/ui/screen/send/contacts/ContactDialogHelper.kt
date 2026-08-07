package network.tos.wallet.app.ui.screen.send.contacts

import android.content.Context
import network.tos.wallet.data.contacts.entities.ContactEntity
import network.tos.wallet.localization.Localization
import uikit.dialog.alert.AlertDialog

object ContactDialogHelper {

    fun delete(context: Context, contact: ContactEntity, confirm: () -> Unit) {
        val builder = AlertDialog.Builder(context)
        builder.setMessage(context.getString(Localization.contact_delete_dialog, contact.name))
        builder.setPositiveButton(Localization.cancel)
        builder.setNegativeButton(Localization.delete) { dialog ->
            confirm()
            dialog.dismiss()
        }
        builder.show()
    }

}