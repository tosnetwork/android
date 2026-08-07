package network.tos.wallet.app.ui.screen.wallet.main.list.holder

import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatTextView
import androidx.recyclerview.widget.RecyclerView
import network.tos.extensions.locale
import network.tos.wallet.app.helper.DateHelper
import network.tos.wallet.app.ui.screen.dns.renew.DNSRenewScreen
import network.tos.wallet.app.ui.screen.dns.renew.DNSRenewViewModel
import network.tos.wallet.app.ui.screen.send.transaction.SendTransactionScreen
import network.tos.wallet.app.ui.screen.wallet.main.list.Item
import network.tos.wallet.app.R
import network.tos.wallet.localization.Localization
import kotlinx.coroutines.launch

class ReNewDomainsHolder(
    parent: ViewGroup
): Holder<Item.RenewDomains>(parent, R.layout.view_wallet_renew_domains) {

    private val untilDateString: String by lazy {
        DateHelper.untilDate(
            locale = context.locale
        )
    }

    private val textView = findViewById<AppCompatTextView>(R.id.text)

    init {
        itemView.layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
    }

    override fun onBind(item: Item.RenewDomains) {
        textView.text = context.getString(Localization.wallet_renew_dns, item.items.size, untilDateString)
        itemView.setOnClickListener {
            navigation?.add(DNSRenewScreen.newInstance(item.wallet, item.items))
        }
    }
}