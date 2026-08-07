package network.tos.wallet.app.ui.screen.send.contacts.edit

import android.app.Application
import androidx.lifecycle.viewModelScope
import network.tos.wallet.app.ui.base.BaseWalletVM
import network.tos.wallet.api.API
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.contacts.ContactsRepository
import network.tos.wallet.data.contacts.entities.ContactEntity
import kotlinx.coroutines.launch

class EditContactViewModel(
    app: Application,
    private val wallet: WalletEntity,
    private val contact: ContactEntity,
    private val api: API,
    private val contactsRepository: ContactsRepository
): BaseWalletVM(app) {


    fun delete(callback: () -> Unit) {
        viewModelScope.launch {
            contactsRepository.deleteContact(contact.id)
            callback()
        }

    }

    fun save(name: String, callback: () -> Unit) {
        viewModelScope.launch {
            contactsRepository.editContact(contact.id, name)
            callback()
        }
    }
}