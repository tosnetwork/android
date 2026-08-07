package network.tos.wallet.app.ui.screen.settings.theme.list.holder

import android.app.Activity
import android.view.ViewGroup
import network.tos.wallet.app.core.DevSettings
import network.tos.wallet.app.ui.screen.settings.theme.list.Item
import network.tos.wallet.localization.Localization
import uikit.extensions.activity
import uikit.widget.item.ItemSwitchView

class FontSizeHolder(parent: ViewGroup): Holder<Item.FontSize>(ItemSwitchView(parent.context)) {

    private val activity: Activity?
        get() = itemView.context.activity

    private val switchView = itemView as ItemSwitchView

    init {
        switchView.text = getString(Localization.default_font_size)
        switchView.setChecked(DevSettings.ignoreSystemFontSize, false)
        switchView.doOnCheckedChanged = { isChecked, byUser ->
            if (byUser) {
                DevSettings.ignoreSystemFontSize = isChecked
                activity?.recreate()
            }
        }
    }

    override fun onBind(item: Item.FontSize) {

    }
}