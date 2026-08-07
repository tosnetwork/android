package network.tos.wallet.app.ui.screen.send.contacts.edit

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import network.tos.extensions.getParcelableCompat
import network.tos.wallet.app.extensions.copyToClipboard
import network.tos.wallet.app.extensions.hideKeyboard
import network.tos.wallet.app.koin.walletViewModel
import network.tos.wallet.app.ui.base.WalletContextScreen
import network.tos.wallet.app.ui.screen.send.contacts.ContactDialogHelper
import network.tos.wallet.app.R
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.contacts.entities.ContactEntity
import org.koin.core.parameter.parametersOf
import uikit.base.BaseFragment
import uikit.extensions.pinToBottomInsets
import uikit.widget.InputView
import uikit.widget.ModalHeader

class EditContactScreen(wallet: WalletEntity): WalletContextScreen(R.layout.fragment_contact_edit, wallet), BaseFragment.Modal {

    override val fragmentName: String = "EditContactScreen"

    private val contact: ContactEntity by lazy { requireArguments().getParcelableCompat(ARG_CONTACT)!! }

    override val viewModel: EditContactViewModel by walletViewModel {
        parametersOf(contact)
    }

    private lateinit var headerView: ModalHeader
    private lateinit var nameView: InputView
    private lateinit var addressView: InputView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        headerView = view.findViewById(R.id.header)
        headerView.onCloseClick = { finish() }

        nameView = view.findViewById(R.id.name)
        nameView.text = contact.name

        addressView = view.findViewById(R.id.address)
        addressView.text = contact.address
        addressView.isEnabled = false

        val saveButton = view.findViewById<Button>(R.id.save)
        saveButton.setOnClickListener { save() }

        nameView.doOnTextChange = {
            saveButton.isEnabled = it.isNotEmpty()
        }

        view.findViewById<Button>(R.id.delete).setOnClickListener { delete() }

        view.pinToBottomInsets()
    }

    override fun onResume() {
        super.onResume()
        nameView.focus()
    }

    override fun onPause() {
        super.onPause()
        hideKeyboard()
    }

    private fun delete() {
        hideKeyboard()
        ContactDialogHelper.delete(requireContext(), contact) {
            viewModel.delete { finish() }
        }
    }

    private fun save() {
        hideKeyboard()
        viewModel.save(nameView.text) {
            finish()
        }
    }

    companion object {

        private const val ARG_CONTACT = "contact"

        fun newInstance(wallet: WalletEntity, contact: ContactEntity): EditContactScreen {
            val fragment = EditContactScreen(wallet)
            fragment.putParcelableArg(ARG_CONTACT, contact)
            return fragment
        }
    }
}