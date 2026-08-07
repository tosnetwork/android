package network.tos.signer.screen.main.list.holder

import android.view.ViewGroup
import androidx.annotation.LayoutRes
import network.tos.signer.screen.main.list.MainItem

abstract class MainHolder<I: MainItem>(
    parent: ViewGroup,
    @LayoutRes resId: Int
): network.tos.uikit.list.BaseListHolder<I>(parent, resId)
