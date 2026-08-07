package network.tos.wallet.app.ui.screen.battery.recharge.list.holder

import android.view.ViewGroup
import android.widget.Button
import network.tos.wallet.app.ui.screen.battery.recharge.list.Item
import network.tos.wallet.app.R

class ButtonHolder(
    parent: ViewGroup,
    private val onContinue: () -> Unit,
) : Holder<Item.Button>(parent, R.layout.fragment_recharge_button) {

    private val buttonView = itemView.findViewById<Button>(R.id.button)

    override fun onBind(item: Item.Button) {
        buttonView.setOnClickListener {
            onContinue()
        }
        buttonView.isEnabled = item.isEnabled
    }
}