package network.tos.wallet.app.extensions

import network.tos.wallet.data.core.Theme
import ui.theme.AppColorScheme

fun Theme.compose(): AppColorScheme {
    return when (key) {
        "dark" -> ui.theme.appColorSchemeDark()
        "light" -> ui.theme.appColorSchemeLight()
        else -> ui.theme.appColorSchemeBlue()
    }
}