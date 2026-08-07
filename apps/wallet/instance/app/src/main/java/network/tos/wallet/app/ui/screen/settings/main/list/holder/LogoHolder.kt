package network.tos.wallet.app.ui.screen.settings.main.list.holder

import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatTextView
import network.tos.extensions.appVersionCode
import network.tos.extensions.appVersionName
import network.tos.wallet.app.ui.screen.dev.DevScreen
import network.tos.wallet.app.ui.screen.settings.main.list.Item
import network.tos.wallet.app.BuildConfig
import network.tos.wallet.app.R
import network.tos.wallet.localization.Localization
import uikit.navigation.Navigation

class LogoHolder(
    parent: ViewGroup,
    onClick: ((Item) -> Unit)
): Holder<Item.Logo>(parent, R.layout.view_settings_logo, onClick) {

    private val versionView = findViewById<AppCompatTextView>(R.id.version)

    init {
        // The developer screen exposes migration tooling that can surface legacy
        // secret material (mnemonic/passcode dumps) and a TonConnect log toggle.
        // It must never be reachable in a production build, so the entry point is
        // gated behind the debug build flag.
        if (BuildConfig.DEBUG) {
            itemView.setOnClickListener {
                Navigation.from(context)?.add(DevScreen.newInstance())
            }
        }
    }

    override fun onBind(item: Item.Logo) {
        val builder = StringBuilder()
        builder.append(context.getString(Localization.version, context.appVersionName, context.appVersionCode))
        builder.append("\n")
        builder.append(item.installerSource.title)

        versionView.text = builder
    }


}