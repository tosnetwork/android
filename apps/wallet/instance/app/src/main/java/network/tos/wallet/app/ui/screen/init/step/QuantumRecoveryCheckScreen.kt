package network.tos.wallet.app.ui.screen.init.step

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.Toast
import android.view.ViewGroup
import kotlinx.coroutines.launch
import network.tos.wallet.app.BuildConfig
import network.tos.wallet.app.R
import network.tos.wallet.app.ui.screen.init.InitViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel
import uikit.base.BaseFragment
import uikit.widget.HeaderView
import uikit.widget.TextHeaderView
import uikit.widget.WordInput

class QuantumRecoveryCheckScreen : BaseFragment(R.layout.fragment_init_quantum_recovery_check) {
    override val fragmentName = "QuantumRecoveryCheckScreen"
    override val secure: Boolean = !BuildConfig.DEBUG
    private val initViewModel: InitViewModel by viewModel(ownerProducer = { requireParentFragment() })
    private lateinit var inputs: List<WordInput>

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<HeaderView>(R.id.header).doOnCloseClick = { initViewModel.routePopBackStack() }
        val indexes = initViewModel.newWalletRecoveryCheckIndices()
        view.findViewById<TextHeaderView>(R.id.text).desciption = getString(
            R.string.quantum_backup_check_hint, indexes[0] + 1, indexes[1] + 1, indexes[2] + 1
        )
        val container = view.findViewById<uikit.widget.ColumnLayout>(R.id.inputs)
        inputs = indexes.mapIndexed { index, wordIndex ->
            WordInput(requireContext()).apply {
                setIndex(wordIndex + 1)
                doOnTextChanged = { checkButton(view) }
                doOnNext = { if (index < indexes.lastIndex) inputs.getOrNull(index + 1)?.focus() else submit(view) }
            }.also { input ->
                val params = android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                container.addView(input, params)
            }
        }
        view.findViewById<Button>(R.id.button).setOnClickListener { submit(view) }
        checkButton(view)
        inputs.firstOrNull()?.focus()
    }

    private fun checkButton(view: View) {
        view.findViewById<Button>(R.id.button).isEnabled = inputs.size == 3 && inputs.all { it.text.isNotBlank() }
    }

    private fun submit(view: View) {
        val answers = inputs.map { it.text }
        if (initViewModel.confirmNewWalletRecoveryPhrase(answers)) return
        Toast.makeText(requireContext(), R.string.quantum_backup_check_error, Toast.LENGTH_SHORT).show()
        inputs.forEachIndexed { index, input -> input.setError(!initViewModel.newWalletRecoveryWordIsCorrect(index, answers[index])) }
    }

    companion object { fun newInstance() = QuantumRecoveryCheckScreen() }
}
