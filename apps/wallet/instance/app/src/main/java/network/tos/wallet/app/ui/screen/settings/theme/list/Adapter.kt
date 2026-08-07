package network.tos.wallet.app.ui.screen.settings.theme.list

import android.view.ViewGroup
import network.tos.wallet.app.ui.screen.settings.theme.list.holder.FontSizeHolder
import network.tos.wallet.app.ui.screen.settings.theme.list.holder.Holder
import network.tos.wallet.app.ui.screen.settings.theme.list.holder.IconHolder
import network.tos.wallet.app.ui.screen.settings.theme.list.holder.SpaceHolder
import network.tos.wallet.app.ui.screen.settings.theme.list.holder.ThemeHolder
import network.tos.wallet.app.ui.screen.settings.theme.list.holder.TitleHolder
import network.tos.uikit.list.BaseListAdapter
import network.tos.uikit.list.BaseListHolder
import network.tos.uikit.list.BaseListItem

class Adapter(
    private val onClickTheme: (item: Item.Theme) -> Unit
): BaseListAdapter() {

    override fun createHolder(parent: ViewGroup, viewType: Int): BaseListHolder<out BaseListItem> {
        return when (viewType) {
            Item.TYPE_THEME -> ThemeHolder(parent, onClickTheme)
            Item.TYPE_TITLE -> TitleHolder(parent)
            Item.TYPE_ICON -> IconHolder(parent)
            Item.TYPE_SPACE -> SpaceHolder(parent)
            Item.TYPE_FONT_SIZE -> FontSizeHolder(parent)
            else -> throw IllegalArgumentException("Unknown viewType: $viewType")
        }
    }
}