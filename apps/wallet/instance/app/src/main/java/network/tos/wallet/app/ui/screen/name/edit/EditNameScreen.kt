package network.tos.wallet.app.ui.screen.name.edit

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.lifecycle.lifecycleScope
import network.tos.wallet.app.koin.walletViewModel
import network.tos.wallet.app.ui.base.BaseWalletScreen
import network.tos.wallet.app.ui.base.ScreenContext
import network.tos.wallet.app.ui.base.WalletContextScreen
import network.tos.wallet.app.ui.component.label.LabelEditorView
import network.tos.wallet.app.R
import network.tos.wallet.data.account.Wallet
import network.tos.wallet.data.account.entities.WalletEntity
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.parameter.parametersOf
import uikit.base.BaseFragment
import uikit.extensions.collectFlow
import uikit.extensions.doKeyboardAnimation
import uikit.widget.HeaderView

class EditNameScreen(wallet: WalletEntity): WalletContextScreen(R.layout.fragment_name_edit, wallet), BaseFragment.BottomSheet {

    override val fragmentName: String = "EditNameScreen"

    override val viewModel: EditNameViewModel by walletViewModel()

    private lateinit var editorView: LabelEditorView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val headerView = view.findViewById<HeaderView>(R.id.header)
        headerView.doOnActionClick = { finish() }

        editorView = view.findViewById(R.id.editor)
        editorView.doOnDone = ::saveLabel
        editorView.name = screenContext.wallet.label.name
        editorView.emoji = screenContext.wallet.label.emoji
        editorView.color = screenContext.wallet.label.color

        view.doKeyboardAnimation { offset, progress, showKeyboard ->
            editorView.setBottomOffset(offset, progress)
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { editorView.loadEmoji() }
    }

    override fun onPause() {
        viewModel.save(editorView.name, editorView.emoji, editorView.color)
        super.onPause()
    }

    private fun saveLabel(name: String, emoji: String, color: Int) {
        viewModel.save(name, emoji, color)
        finish()
    }

    override fun onDragging() {
        super.onDragging()
        editorView.removeFocus()
    }

    companion object {

        fun newInstance(wallet: WalletEntity) = EditNameScreen(wallet)
    }
}