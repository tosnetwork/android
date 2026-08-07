package network.tos.wallet.app.core.history.list.holder

import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatTextView
import network.tos.wallet.app.core.history.list.item.HistoryItem
import network.tos.wallet.app.R

class HistoryEmptyHolder(
    parent: ViewGroup
): HistoryHolder<HistoryItem.Empty>(parent, R.layout.view_history_empty) {

    private val textView = findViewById<AppCompatTextView>(R.id.text)

    override fun onBind(item: HistoryItem.Empty) {
        textView.text = item.title
    }

}