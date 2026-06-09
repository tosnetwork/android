package ui.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.ReadOnlyComposable

object UIKit {
    val colorScheme: AppColorScheme
        @Composable
        @ReadOnlyComposable
        get() = LocalAppColorScheme.current

    val typography: UIKitTypography
        @Composable
        @ReadOnlyComposable
        get() = LocalTypography.current
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@NonRestartableComposable
fun UIKit(
    colorScheme: AppColorScheme,
    content: @Composable () -> Unit
) {

    val typography = rememberAppTypography()

    CompositionLocalProvider(
        LocalAppColorScheme provides colorScheme,
        LocalTypography provides typography,
        LocalRippleConfiguration provides colorScheme.rippleConfiguration,
        LocalIndication provides ripple()
    ) {
        content()
    }
}
