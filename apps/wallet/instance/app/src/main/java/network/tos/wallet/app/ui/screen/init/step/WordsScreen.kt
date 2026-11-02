package network.tos.wallet.app.ui.screen.init.step

import android.os.Bundle
import android.os.Build
import android.text.Editable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.app.AlertDialog
import network.tos.blockchain.TosV1Mnemonic
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import network.tos.blockchain.MnemonicHelper
import network.tos.blockchain.ton.TonMnemonic
import network.tos.wallet.data.account.pq.QuantumNetworkProfile
import network.tos.wallet.app.core.AnalyticsHelper
import network.tos.wallet.app.extensions.clipboardText
import network.tos.wallet.app.extensions.hideKeyboard
import network.tos.wallet.app.extensions.toast
import network.tos.wallet.app.ui.component.WordEditText
import network.tos.wallet.app.ui.screen.init.InitViewModel
import network.tos.wallet.app.BuildConfig
import network.tos.wallet.app.R
import network.tos.uikit.color.backgroundContentTintColor
import network.tos.uikit.color.iconPrimaryColor
import network.tos.uikit.color.textPrimaryColor
import network.tos.wallet.localization.Localization
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.ton.mnemonic.Mnemonic
import uikit.base.BaseFragment
import uikit.drawable.FooterDrawable
import uikit.extensions.clear
import uikit.extensions.collectFlow
import uikit.extensions.doKeyboardAnimation
import uikit.extensions.dp
import uikit.extensions.getCurrentFocusEditText
import uikit.extensions.getViews
import uikit.extensions.hideKeyboard
import uikit.extensions.scrollDown
import uikit.extensions.scrollView
import uikit.extensions.withAlpha
import uikit.navigation.Navigation.Companion.navigation
import uikit.widget.ColumnLayout
import uikit.widget.LoaderView
import uikit.widget.RowLayout
import uikit.widget.TextHeaderView

// TODO Need to refactor this screen
class WordsScreen: BaseFragment(R.layout.fragment_init_words) {

    override val fragmentName: String = "WordsScreen"

    private val initViewModel: InitViewModel by viewModel(ownerProducer = { requireParentFragment() })

    override val secure: Boolean = !BuildConfig.DEBUG

    private lateinit var scrollView: NestedScrollView
    private lateinit var contentView: ColumnLayout
    private lateinit var button: Button
    private lateinit var loaderView: LoaderView
    private lateinit var suggestionsView: RowLayout
    private lateinit var words24View: AppCompatTextView
    private lateinit var titleView: TextHeaderView

    private val isVisibleSuggestions: Boolean
        get() = suggestionsView.visibility == View.VISIBLE && suggestionsView.alpha > 0f

    private lateinit var wordInputs: List<WordEditText>

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        titleView = view.findViewById(R.id.header_title)

        words24View = view.findViewById(R.id.words_24)
        words24View.setOnClickListener {
            if (initViewModel.wordsCount != WORDS24) {
                setWordsCount(WORDS24)
            }
        }

        scrollView = view.findViewById(R.id.scroll)

        contentView = view.findViewById(R.id.content)

        wordInputs = contentView.findViewById<ColumnLayout>(R.id.inputs).getViews().filterIsInstance<WordEditText>()
        // Recovery words are secrets. Do not serialize editable field values
        // into Activity/Fragment saved view state or offer them to Autofill.
        wordInputs.forEach { input ->
            input.isSaveEnabled = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                input.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            }
        }

        button = view.findViewById(R.id.button)
        button.setOnClickListener { next() }

        loaderView = view.findViewById(R.id.loader)
        loaderView.setTrackColor(requireContext().iconPrimaryColor.withAlpha(.32f))
        loaderView.setColor(requireContext().iconPrimaryColor)

        for ((index, wordInput) in wordInputs.withIndex()) {
            wordInput.doOnFocusChanged = { onFocusChange(index, it) }
            wordInput.doOnTextChanged = { onTextChanged(index, it) }
            wordInput.imeOptions = if (isLastIndex(index)) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT
            wordInput.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_NEXT && isVisibleSuggestions && suggestionsView.childCount == 1) {
                    autoSetWord(index)
                }
                if (isLastIndex(index) && actionId == EditorInfo.IME_ACTION_DONE) {
                    next()
                    true
                } else if (isLastIndex(index) && actionId == EditorInfo.IME_ACTION_NEXT) {
                    nextInput(index)
                    true
                } else {
                    false
                }
            }
        }

        suggestionsView = view.findViewById(R.id.suggestions)
        suggestionsView.background = FooterDrawable(requireContext()).apply {
            setDivider(true)
            setColor(requireContext().backgroundContentTintColor)
        }

        collectFlow(initViewModel.uiTopOffset) {
            contentView.updatePadding(top = it)
        }

        scrollView.doKeyboardAnimation { offset, progress, _ ->
            scrollView.updatePadding(bottom = offset)
            suggestionsView.translationY = -offset.toFloat()
            suggestionsView.alpha = progress
        }
        getCountFromClipboard()
    }

    private fun getCountFromClipboard() {
        setWordsCount(WORDS24)
    }

    private fun isLastIndex(index: Int): Boolean {
        return initViewModel.wordsCount - 1 == index
    }

    private fun setWordsCount(count: Int) {
        if (count != WORDS24) {
            return
        }
        words24View.setBackgroundResource(uikit.R.drawable.bg_content_tint_16)
        initViewModel.wordsCount = count
        wordInputs[11].imeOptions = EditorInfo.IME_ACTION_NEXT
        initViewModel.wordsCount = count
        wordInputs[count - 1].imeOptions = EditorInfo.IME_ACTION_DONE
        updateVisibleInputs()
    }

    private fun updateVisibleInputs() {
        for ((index, input) in wordInputs.withIndex()) {
            input.visibility = if (index < initViewModel.wordsCount) View.VISIBLE else View.GONE
        }
        checkWords()
    }

    private fun nextInput(index: Int) {
        val nextView = wordInputs.getOrNull(index + 1) ?: return
        nextView.requestFocus()
        scrollView.scrollView(nextView, false)
    }

    private fun autoSetWord(index: Int) {
        val inputView = wordInputs.getOrNull(index) ?: return
        val text = inputView.text.toString()
        lifecycleScope.launch(Dispatchers.IO) {
            val word = TonMnemonic.findWord(text)
            if (!word.isNullOrBlank()) {
                withContext(Dispatchers.Main) {
                    inputView.setText(word)
                    inputView.checkValue()
                    suggestionsView.visibility = View.GONE
                }
            }
        }
    }

    private fun next() {
        lifecycleScope.launch {
            val words = getMnemonic()
            val (native, legacy) = withContext(Dispatchers.IO) {
                TosV1Mnemonic.isValid(words) to TosV1Mnemonic.isLegacyValid(words)
            }
            if (!native && !legacy) {
                navigation?.toast(Localization.incorrect_phrase)
                return@launch
            }
            if (native) {
                val options = if (legacy) arrayOf(
                    getString(R.string.restore_tos_wallet),
                    getString(R.string.restore_quantum_wallet),
                    getString(R.string.restore_legacy_wallet)
                ) else arrayOf(getString(R.string.restore_tos_wallet), getString(R.string.restore_quantum_wallet))
                // AlertDialog's message content replaces its item list. Keep the
                // wallet-format choices visible for native and ambiguous phrases.
                AlertDialog.Builder(requireContext()).setTitle(R.string.recovery_profile_title)
                    .setItems(options) { _, which ->
                        when (which) {
                            0 -> continueRecovery(words, TosV1Mnemonic.Profile.TOS)
                            1 -> continueQuantumRecovery(words)
                            2 -> continueRecovery(words, TosV1Mnemonic.Profile.LEGACY)
                        }
                    }.setNegativeButton(android.R.string.cancel, null).show()
            } else if (legacy) {
                AlertDialog.Builder(requireContext()).setTitle(R.string.legacy_recovery_title)
                    .setMessage(R.string.legacy_recovery_hint)
                    .setPositiveButton(Localization.continue_action) { _, _ -> continueRecovery(words, TosV1Mnemonic.Profile.LEGACY) }
                    .setNegativeButton(android.R.string.cancel, null).show()
            } else continueRecovery(words)
        }
    }

    private fun continueRecovery(words: List<String>, profile: TosV1Mnemonic.Profile? = null) {
        lifecycleScope.launch {
            setLoading()
            if (TonMnemonic.isValidTONKeychain(words)) {
                navigation?.toast(Localization.multi_account_secret_wrong)
            } else if (initViewModel.watchRecoveryAccountId != null && initViewModel.getRecoveryWatchWallet(words, profile) == null) {
                navigation?.toast(Localization.mnemonic_match_error)
            } else {
                if (!initViewModel.setMnemonic(words, profile)) {
                    navigation?.toast(Localization.incorrect_phrase)
                }
            }
            setDefault()
        }
    }

    private fun continueQuantumRecovery(words: List<String>) {
        val profile = runCatching { QuantumNetworkProfile.effective(requireContext()) }.getOrElse {
            navigation?.toast(it.localizedMessage ?: getString(R.string.quantum_network_invalid))
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.quantum_restore_network_confirmation_title)
            .setMessage(getString(R.string.quantum_restore_network_confirmation,
                profile.globalId, profile.rootHash, profile.fileHash))
            .setPositiveButton(R.string.quantum_restore_network_continue) { _, _ ->
                lifecycleScope.launch {
                    setLoading()
                    try {
                        if (TonMnemonic.isValidTONKeychain(words)) {
                            navigation?.toast(Localization.multi_account_secret_wrong)
                        } else {
                            initViewModel.preflightQuantumRecovery(profile)
                            if (!initViewModel.setQuantumMnemonic(words, profile)) {
                                navigation?.toast(Localization.incorrect_phrase)
                            } else {
                                // The process-local recovery session now owns
                                // the phrase; remove the duplicate from the
                                // input form before it remains on the back stack.
                                wordInputs.forEach { it.text?.clear() }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        initViewModel.quantumRecoveryNetworkUnavailable()
                    } finally {
                        setDefault()
                    }
                }
            }
            .setNeutralButton(R.string.quantum_restore_network_change) { _, _ ->
                network.tos.wallet.app.ui.screen.pq.QuantumNetworkDialog.show(requireContext())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setLoading() {
        button.text = ""
        button.isEnabled = false
        loaderView.visibility = View.VISIBLE
        wordInputs.forEach { it.isEnabled = false }
    }

    private fun setDefault() {
        button.setText(Localization.continue_action)
        button.isEnabled = false
        loaderView.visibility = View.GONE
        wordInputs.forEach { it.isEnabled = true }
        checkWords()
    }

    private fun onFocusChange(index: Int, hasFocus: Boolean) {
        if (hasFocus) {
            checkSuggestions(index, wordInputs[index].text.toString())
        } else {
            suggestionsView.visibility = View.GONE
        }
    }

    private fun onTextChanged(index: Int, editable: Editable) {
        if (index == 0) {
            val words = TonMnemonic.parseMnemonic(editable.toString())
            postOnAnimation {
                if (words.isNotEmpty()) {
                    applyWords(words)
                } else {
                    checkSuggestions(index, editable.toString())
                }
            }
        } else {
            postOnAnimation {
                checkWords()
                checkSuggestions(index, editable.toString())
            }
        }
    }

    private fun checkSuggestions(index: Int, text: String) {
        if (!wordInputs[index].isFocused || text.isEmpty()) {
            suggestionsView.visibility = View.GONE
        } else {
            lifecycleScope.launch(Dispatchers.IO) {
                val words = TonMnemonic.findWords(text).take(3)
                if (words.size == 1 && words.first().equals(text, true)) {
                    setSuggestions(index, emptyList())
                } else {
                    setSuggestions(index, words)
                }
            }
        }
    }

    private suspend fun setSuggestions(
        index: Int,
        words: List<String>
    ) = withContext(Dispatchers.Main) {
        if (words.isEmpty()) {
            suggestionsView.visibility = View.GONE
        } else {
            suggestionsView.removeAllViews()
            for (word in words) {
                val textView = AppCompatTextView(requireContext()).apply {
                    setTextAppearance(uikit.R.style.TextAppearance_Label2)
                    setTextColor(requireContext().textPrimaryColor)
                    text = word
                    gravity = Gravity.CENTER
                    setOnClickListener { setWord(index, word) }
                }
                suggestionsView.addView(textView, LinearLayoutCompat.LayoutParams(LinearLayoutCompat.LayoutParams.MATCH_PARENT, LinearLayoutCompat.LayoutParams.MATCH_PARENT, 1f))
            }
            suggestionsView.visibility = View.VISIBLE
        }
    }

    private fun setWord(index: Int, value: String) {
        if (suggestionsView.alpha == 0f || suggestionsView.visibility != View.VISIBLE) {
            return
        }
        suggestionsView.visibility = View.GONE
        val inputView = wordInputs.getOrNull(index) ?: return
        inputView.setText(value)

        val nextFocusInput = inputView.focusSearch(View.FOCUS_DOWN) as? WordEditText

        if (nextFocusInput != null && nextFocusInput.visibility == View.VISIBLE) {
            nextFocusInput.requestFocus()
            if (index > (initViewModel.wordsCount - 4)) {
                scrollView.scrollDown(true)
            } else {
                scrollView.scrollView(
                    view = nextFocusInput,
                    smooth = true,
                    top = (-128).dp
                )
            }
        }
    }

    private fun applyWords(words: List<String>) {
        if (words.size > 1) {
            wordInputs.first().clear()
            setWords(words)
        } else {
            checkWords()
        }
    }

    private fun checkWords(delay: Long = 0) {
        lifecycleScope.launch {
            if (delay > 0) {
                delay(delay)
            }
            button.isEnabled = getMnemonic().size == initViewModel.wordsCount
        }
    }

    private suspend fun getMnemonic(): List<String> = withContext(Dispatchers.IO) {
        val words = wordInputs
            .map { it.text?.toString() }
            .filter { TonMnemonic.isValid(it) }
            .filterNotNull()
            .take(initViewModel.wordsCount)

        if (words.size == wordInputs.count { it.visibility == View.VISIBLE }) {
            words
        } else {
            emptyList()
        }
    }

    private fun setWords(list: List<String>) {
        for (i in list.indices) {
            wordInputs.getOrNull(i)?.setText(list[i])
        }
        if (list.size == initViewModel.wordsCount) {
            hideKeyboard()
            scrollView.scrollDown(true)
            checkWords(500)
        }
    }

    companion object {

        private const val WORDS24 = 24

        private const val ARG_TESTNET = "testnet"

        fun newInstance(testnet: Boolean): WordsScreen {
            val fragment = WordsScreen()
            fragment.arguments = Bundle().apply {
                putBoolean(ARG_TESTNET, testnet)
            }
            return fragment
        }
    }
}
