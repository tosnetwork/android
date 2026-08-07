package network.tos.wallet.app.core.history.list.holder

import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatTextView
import network.tos.wallet.app.core.history.list.item.HistoryItem
import network.tos.wallet.app.R

class HistoryFailedHolder(
    parent: ViewGroup
): HistoryHolder<HistoryItem.Failed>(parent, R.layout.view_history_failed) {

    private val textView = findViewById<AppCompatTextView>(R.id.text)

    override fun onBind(item: HistoryItem.Failed) {
        textView.text = item.text
    }

}