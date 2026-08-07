package network.tos.icu.format

import android.content.Context
import android.graphics.drawable.Drawable
import network.tos.icu.R

internal class TONSymbolSpan(context: Context): CustomSymbolSpan(context, R.drawable.ic_ton_symbol, R.drawable.ic_ton_bold_symbol) {

    override fun setSize(selectedDrawable: Drawable, size: Float) {
        super.setSize(selectedDrawable,size * 1.14f)
    }
}