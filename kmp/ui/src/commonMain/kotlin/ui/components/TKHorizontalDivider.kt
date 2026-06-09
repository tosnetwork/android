package ui.components

import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ui.theme.UIKit

@Composable
@NonRestartableComposable
fun TKHorizontalDivider(
    modifier: Modifier = Modifier,
) {
    val color = UIKit.colorScheme.separator.common
    val dividerSize = .5f.dp

    HorizontalDivider(
        color = color,
        thickness = dividerSize,
        modifier = modifier
    )
}