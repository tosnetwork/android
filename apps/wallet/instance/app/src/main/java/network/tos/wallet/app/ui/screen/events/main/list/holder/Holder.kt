package network.tos.wallet.app.ui.screen.events.main.list.holder

import android.view.ViewGroup
import androidx.annotation.LayoutRes
import network.tos.wallet.app.ui.screen.events.main.list.Item
import network.tos.uikit.list.BaseListHolder

abstract class Holder<I: Item>(
    parent: ViewGroup,
    @LayoutRes resId: Int,
): BaseListHolder<I>(parent, resId)