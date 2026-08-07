package network.tos.wallet.app.ui.screen.browser.base

import android.app.Application
import androidx.core.view.WindowInsetsCompat
import network.tos.extensions.MutableEffectFlow
import network.tos.wallet.app.Environment
import network.tos.wallet.app.ui.base.BaseWalletVM
import network.tos.wallet.api.API
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.browser.BrowserRepository
import network.tos.wallet.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext

class BrowserBaseViewModel(
    app: Application,
    private val wallet: WalletEntity,
    private val browserRepository: BrowserRepository,
    private val settingsRepository: SettingsRepository,
    private val api: API,
    private val environment: Environment
): BaseWalletVM(app) {

    private val _childBottomScrolled = MutableEffectFlow<Boolean>()
    val childBottomScrolled = _childBottomScrolled.asSharedFlow()

    private val _insetsRootFlow = MutableEffectFlow<WindowInsetsCompat>()
    val insetsRootFlow = _insetsRootFlow.asSharedFlow()

    fun setInsetsRoot(value: WindowInsetsCompat) {
        _insetsRootFlow.tryEmit(value)
    }

    fun setBottomScrolled(value: Boolean) {
        _childBottomScrolled.tryEmit(value)
    }

    suspend fun hasCategory(category: String): Boolean = withContext(Dispatchers.IO) {
        val categories = browserRepository.loadCategories(
            country = environment.country,
            testnet = wallet.testnet,
            locale = settingsRepository.getLocale()
        )
        categories.any { it == category }
    }

}