package network.tos.wallet.app.ui.screen.dev.list.launcher

import android.content.Context
import androidx.annotation.DrawableRes
import network.tos.wallet.app.core.LauncherIcon
import network.tos.uikit.list.BaseListItem

data class Item(val icon: LauncherIcon): BaseListItem() {

    @get:DrawableRes
    val iconRes: Int
        get() = icon.iconRes

    val title: String
        get() = icon.type

    fun isEnabled(context: Context): Boolean {
        return icon.isEnabled(context)
    }
}