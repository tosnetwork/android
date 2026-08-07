package network.tos.wallet.app.ui.screen.backup.main.list.holder

import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatTextView
import network.tos.wallet.app.ui.screen.backup.main.list.Item
import network.tos.wallet.app.R
import network.tos.uikit.color.accentOrangeColor
import network.tos.uikit.color.accentRedColor
import network.tos.uikit.color.constantBlackColor
import network.tos.uikit.color.stateList
import network.tos.uikit.color.textPrimaryColor
import network.tos.wallet.localization.Localization
import uikit.extensions.withAlpha

class AlertHolder(parent: ViewGroup): Holder<Item.Alert>(parent, R.layout.view_backup_alert) {

    private val alertView = findViewById<AppCompatTextView>(R.id.alert)

    override fun onBind(item: Item.Alert) {
        alertView.text = context.getString(Localization.backup_alert_message, item.balanceFormat)
        if (item.red) {
            alertView.backgroundTintList = context.accentRedColor.stateList
            alertView.setTextColor(context.textPrimaryColor.withAlpha(.76f))
        } else {
            alertView.backgroundTintList = context.accentOrangeColor.stateList
            alertView.setTextColor(context.constantBlackColor.withAlpha(.76f))
        }
    }

}