package network.tos.wallet.app.ui.screen.wallet.main.list.holder

import android.view.ViewGroup
import com.facebook.shimmer.ShimmerFrameLayout
import network.tos.wallet.app.extensions.applyColors
import network.tos.wallet.app.ui.screen.wallet.main.list.Item
import network.tos.wallet.app.R

class SkeletonHolder(parent: ViewGroup): Holder<Item.Skeleton>(parent, R.layout.view_wallet_skeleton) {

    private val shimmerView = findViewById<ShimmerFrameLayout>(R.id.shimmer)

    init {
        shimmerView.applyColors()
    }

    override fun onBind(item: Item.Skeleton) {

    }

}