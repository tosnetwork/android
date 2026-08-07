package network.tos.wallet.app.ui.screen.purchase.list.holder

import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatTextView
import network.tos.wallet.app.ui.screen.purchase.list.Item
import network.tos.wallet.app.R
import network.tos.wallet.data.purchase.entity.PurchaseMethodEntity
import uikit.extensions.drawable
import uikit.widget.AsyncImageView

class MethodHolder(
    parent: ViewGroup,
    private val onClick: (PurchaseMethodEntity, String) -> Unit
): Holder<Item.Method>(parent, R.layout.view_purchase_method) {

    private val iconView = findViewById<AsyncImageView>(R.id.icon)
    private val titleView = findViewById<AppCompatTextView>(R.id.title)
    private val descriptionView = findViewById<AppCompatTextView>(R.id.description)

    override fun onBind(item: Item.Method) {
        itemView.setOnClickListener { onClick(item.entity, item.categoryType) }
        itemView.background = item.position.drawable(context)
        iconView.setImageURI(item.iconUri, this)
        titleView.text = item.title
        descriptionView.text = item.description
    }

}