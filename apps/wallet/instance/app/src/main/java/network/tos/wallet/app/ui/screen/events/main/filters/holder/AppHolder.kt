package network.tos.wallet.app.ui.screen.events.main.filters.holder

import android.view.View
import android.view.ViewGroup
import androidx.core.view.updatePadding
import network.tos.wallet.app.ui.screen.events.main.filters.FilterItem
import network.tos.wallet.app.R
import network.tos.uikit.color.constantWhiteColor
import uikit.extensions.dp
import uikit.extensions.drawable
import uikit.extensions.withAlpha
import uikit.widget.AsyncImageView

class AppHolder(
    parent: ViewGroup,
    private val onClick: (item: FilterItem) -> Unit
): Holder<FilterItem.App>(parent) {

    private val iconView = findViewById<AsyncImageView>(R.id.icon)

    init {
        itemView.updatePadding(
            left = 6.dp,
            right = 14.dp
        )
        iconView.visibility = View.VISIBLE
        iconView.setPlaceholder(context.drawable(uikit.R.drawable.bg_oval).apply {
            setTint(context.constantWhiteColor.withAlpha(.2f))
        })
    }

    override fun onBind(item: FilterItem.App) {
        itemView.setOnClickListener { onClick(item) }
        titleView.text = item.name
        iconView.setImageURI(item.iconUrl, null)
        updateSelected(item)
    }

    fun updateSelected(item: FilterItem) {
        setSelected(item.selected)
    }

}