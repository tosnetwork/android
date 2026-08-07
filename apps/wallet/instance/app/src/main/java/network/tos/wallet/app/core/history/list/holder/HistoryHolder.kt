package network.tos.wallet.app.core.history.list.holder

import android.view.ViewGroup
import androidx.annotation.LayoutRes
import network.tos.wallet.app.core.history.list.item.HistoryItem
import network.tos.uikit.list.BaseListHolder

abstract class HistoryHolder<I: HistoryItem>(
    parent: ViewGroup,
    @LayoutRes resId: Int
): BaseListHolder<I>(parent, resId)