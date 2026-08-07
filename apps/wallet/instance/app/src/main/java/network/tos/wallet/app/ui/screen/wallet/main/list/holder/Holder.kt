package network.tos.wallet.app.ui.screen.wallet.main.list.holder

import android.view.ViewGroup
import androidx.annotation.LayoutRes
import network.tos.wallet.app.ui.screen.wallet.main.list.Item
import network.tos.uikit.list.BaseListHolder
import uikit.navigation.Navigation

abstract class Holder<I: Item>(
    parent: ViewGroup,
    @LayoutRes resId: Int,
): BaseListHolder<I>(parent, resId) {

    val navigation = Navigation.from(context)
}