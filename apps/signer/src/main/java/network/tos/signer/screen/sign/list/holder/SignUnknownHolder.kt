package network.tos.signer.screen.sign.list.holder

import android.view.ViewGroup
import network.tos.signer.R
import network.tos.signer.screen.sign.list.SignItem
import network.tos.uikit.icon.UIKitIcon
import uikit.extensions.drawable

class SignUnknownHolder(parent: ViewGroup): SignHolder<SignItem.Unknown>(parent) {

    init {
        iconView.setImageResource(UIKitIcon.ic_gear_28)
        titleView.setText(R.string.unknown)
    }

    override fun onBind(item: SignItem.Unknown) {
        itemView.background = item.position.drawable(context)
    }
}