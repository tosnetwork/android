package network.tos.wallet.app.ui.screen.init.step

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import network.tos.wallet.app.BuildConfig
import network.tos.wallet.app.R
import network.tos.wallet.app.ui.screen.init.InitViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel
import uikit.base.BaseFragment
import uikit.widget.HeaderView
import uikit.widget.PhraseWords

class QuantumRecoveryPhraseScreen : BaseFragment(R.layout.fragment_init_quantum_recovery_phrase) {
    override val fragmentName = "QuantumRecoveryPhraseScreen"
    override val secure: Boolean = !BuildConfig.DEBUG
    private val initViewModel: InitViewModel by viewModel(ownerProducer = { requireParentFragment() })

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<HeaderView>(R.id.header).doOnCloseClick = { initViewModel.routePopBackStack() }
        val words = view.findViewById<PhraseWords>(R.id.words)
        val profileView = view.findViewById<TextView>(R.id.network_profile)
        val button = view.findViewById<Button>(R.id.button)
        var revealed = false
        var profileAvailable = false
        button.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val profile = initViewModel.newWalletRecoveryProfile()
                profileView.text = if (profile == null) {
                    getString(R.string.quantum_backup_profile_unavailable)
                } else {
                    profileAvailable = true
                    getString(R.string.quantum_backup_profile_values,
                        profile.globalId, profile.rootHash, profile.fileHash)
                }
                button.isEnabled = profileAvailable
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                profileView.text = getString(R.string.quantum_backup_profile_unavailable)
                button.isEnabled = false
            }
        }
        button.setOnClickListener {
            if (!revealed) {
                button.isEnabled = false
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val phrase = initViewModel.revealNewWalletRecoveryPhrase(requireContext())
                        if (phrase != null) {
                            words.setWords(phrase)
                            words.visibility = View.VISIBLE
                            revealed = true
                            button.setText(R.string.quantum_backup_phrase_continue)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        android.widget.Toast.makeText(requireContext(),
                            R.string.quantum_backup_phrase_unlock_failed, android.widget.Toast.LENGTH_LONG).show()
                    } finally {
                        if (isAdded) button.isEnabled = profileAvailable
                    }
                }
            } else {
                initViewModel.beginNewWalletRecoveryCheck()
            }
        }
    }

    companion object { fun newInstance() = QuantumRecoveryPhraseScreen() }
}
