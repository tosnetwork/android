package ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter

@Composable
actual fun painterResource(id: Int): Painter {
    return androidx.compose.ui.res.painterResource(id = id)
}

actual fun fixAndroidResUrl(url: String): String {
    if (url.startsWith("res:")) {
        val resId = url.replace("res:/", "")
        val packageName = "com.ton_keeper" // TODO replace with actual package name
        return "android.resource://$packageName/$resId"
    }
    return url
}

