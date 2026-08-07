package network.tos.wallet.app.ui.component

import android.content.Context
import android.util.AttributeSet
import android.view.View

class TokenView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    override fun hasOverlappingRendering() = false
}