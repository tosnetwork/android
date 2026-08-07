package network.tos.wallet.app.ui.component.coin.format

import android.text.InputFilter
import android.text.Spanned

class CoinFormattingFilter(
    private val config: CoinFormattingConfig
): InputFilter {

    override fun filter(
        source: CharSequence,
        start: Int,
        end: Int,
        dest: Spanned,
        dstart: Int,
        dend: Int
    ): CharSequence? {
        // Reject the whole edit instead of silently stripping a sign or other unsupported
        // character. Turning "-1" into "1" can reverse the user's transfer intent.
        if (source.any { !it.isDigit() && it.toString() != config.separator && !config.isUnsupportedSeparator(it.toString()) }) {
            return ""
        }
        val isFirst = dstart == 0 && dend == 0
        if (isFirst && (source == CoinFormattingConfig.ZERO || source == config.separator || config.isUnsupportedSeparator(source))) {
            return config.zeroNanoPrefix
        } else if (config.isUnsupportedSeparator(source)) {
            return config.separator
        }
        return null
    }
}
