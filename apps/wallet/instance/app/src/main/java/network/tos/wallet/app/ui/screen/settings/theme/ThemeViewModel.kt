package network.tos.wallet.app.ui.screen.settings.theme

import android.app.Application
import androidx.lifecycle.ViewModel
import network.tos.extensions.recreate
import network.tos.wallet.app.core.LauncherIcon
import network.tos.wallet.app.ui.base.BaseWalletVM
import network.tos.wallet.app.ui.screen.settings.theme.list.Item
import network.tos.uikit.list.ListCell
import network.tos.wallet.data.core.Theme
import network.tos.wallet.data.settings.SettingsRepository
import network.tos.wallet.localization.Localization
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import uikit.extensions.collectFlow

class ThemeViewModel(
    app: Application,
    private val settingsRepository: SettingsRepository
): BaseWalletVM(app) {

    private var currentThemeId = settingsRepository.theme.resId

    private val _uiItemsFlow = MutableStateFlow<List<Item>>(emptyList())
    val uiItemsFlow = _uiItemsFlow.asStateFlow().filter { it.isNotEmpty() }

    init {
        updateValues(currentThemeId)
    }

    fun setTheme(theme: Int) {
        currentThemeId = theme
        updateValues(theme)
        settingsRepository.theme = Theme.getByResId(theme)
        context.recreate()
    }

    private fun updateValues(themeId: Int) {
        val items = mutableListOf<Item>()
        items.add(Item.Title(getString(Localization.color_scheme)))
        for ((index, theme) in Theme.getSupported().withIndex()) {
            val position = ListCell.getPosition(Theme.getSupported().size, index)
            items.add(Item.Theme(
                position = position,
                theme = theme,
                selected = themeId == theme.resId
            ))
        }
        items.add(Item.Space)
        items.add(Item.Title(getString(Localization.app_icon)))
        items.add(Item.Icon(LauncherIcon.Default))
        items.add(Item.Icon(LauncherIcon.Accent))
        items.add(Item.Icon(LauncherIcon.Dark))
        items.add(Item.Icon(LauncherIcon.Light))
        items.add(Item.Space)
        items.add(Item.Title(getString(Localization.other)))
        items.add(Item.FontSize)
        _uiItemsFlow.value = items.toList()
    }
}