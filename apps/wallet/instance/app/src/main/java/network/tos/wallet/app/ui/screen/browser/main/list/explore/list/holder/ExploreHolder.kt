package network.tos.wallet.app.ui.screen.browser.main.list.explore.list.holder

import android.view.ViewGroup
import androidx.annotation.LayoutRes
import network.tos.wallet.app.ui.screen.browser.main.list.explore.list.ExploreItem
import network.tos.uikit.list.BaseListHolder

abstract class ExploreHolder<I: ExploreItem>(
    parent: ViewGroup,
    @LayoutRes resId: Int
): BaseListHolder<I>(parent, resId)
