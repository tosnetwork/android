package uikit.extensions

import android.content.Context
import android.graphics.drawable.Drawable
import network.tos.uikit.color.backgroundContentColor
import network.tos.uikit.list.ListCell
import uikit.drawable.CellBackgroundDrawable

fun ListCell.Position.drawable(
    context: Context,
    backgroundColor: Int = context.backgroundContentColor
): Drawable {
    return CellBackgroundDrawable.create(context, this, backgroundColor)
}