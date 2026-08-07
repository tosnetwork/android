package network.tos.wallet.app.ui.screen.settings.extensions.list.holder

import android.view.ViewGroup
import network.tos.wallet.app.R
import network.tos.uikit.list.BaseListHolder
import network.tos.uikit.list.BaseListItem

abstract class Holder<I: BaseListItem>(parent: ViewGroup, layoutId: Int): BaseListHolder<I>(parent, layoutId) {
}
