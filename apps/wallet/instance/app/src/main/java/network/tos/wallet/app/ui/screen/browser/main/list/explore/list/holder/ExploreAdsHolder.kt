package network.tos.wallet.app.ui.screen.browser.main.list.explore.list.holder

import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import androidx.appcompat.widget.AppCompatTextView
import network.tos.wallet.app.ui.screen.browser.main.list.explore.list.ExploreItem
import network.tos.wallet.app.ui.screen.root.RootActivity
import network.tos.wallet.app.R
import uikit.extensions.activity
import uikit.widget.AsyncImageView

class ExploreAdsHolder(parent: ViewGroup): ExploreHolder<ExploreItem.Ads>(parent, R.layout.view_browser_ads) {

    private val activity: RootActivity?
        get() = context.activity as? RootActivity

    private val iconView = findViewById<AsyncImageView>(R.id.icon)
    private val titleView = findViewById<AppCompatTextView>(R.id.title)
    private val descriptionView = findViewById<AppCompatTextView>(R.id.description)
    private val actionButton = findViewById<Button>(R.id.action)

    override fun onBind(item: ExploreItem.Ads) {
        iconView.setImageURI(item.app.icon, this)
        titleView.text = item.app.name
        descriptionView.text = item.app.description
        actionButton.text = item.button.title

        actionButton.setOnClickListener {
            Log.d("ExploreAdsHolderLog", "url: ${item.uri}")
            activity?.processDeepLink(item.uri, true, context.packageName)
        }
    }

}