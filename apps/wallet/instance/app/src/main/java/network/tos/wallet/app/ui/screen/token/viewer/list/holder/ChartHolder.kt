package network.tos.wallet.app.ui.screen.token.viewer.list.holder

import android.text.SpannableStringBuilder
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.text.color
import network.tos.extensions.locale
import network.tos.icu.Coins
import network.tos.icu.CurrencyFormatter
import network.tos.icu.CurrencyFormatter.withCustomSymbol
import network.tos.icu.Formatter
import network.tos.wallet.app.extensions.getDiffColor
import network.tos.wallet.app.helper.DateHelper
import network.tos.wallet.app.koin.koin
import network.tos.wallet.app.ui.screen.token.viewer.list.Item
import network.tos.wallet.app.view.ChartPeriodView
import network.tos.wallet.app.ui.component.chart.ChartView
import network.tos.wallet.app.R
import network.tos.wallet.api.entity.ChartEntity
import network.tos.wallet.data.rates.RatesRepository
import network.tos.wallet.data.settings.ChartPeriod
import network.tos.wallet.localization.Localization
import uikit.extensions.withAlpha

class ChartHolder(
    parent: ViewGroup,
    private val chartPeriodCallback: (ChartPeriod) -> Unit
): Holder<Item.Chart>(parent, R.layout.view_token_chart) {

    private val ratesRepository: RatesRepository? by lazy {
        context.koin?.get<RatesRepository>()
    }

    private val priceView = findViewById<AppCompatTextView>(R.id.price)
    private val diffView = findViewById<AppCompatTextView>(R.id.diff)
    private val chartView = findViewById<ChartView>(R.id.chart)
    private val periodView = findViewById<ChartPeriodView>(R.id.period)
    private val dateView = findViewById<AppCompatTextView>(R.id.date)

    init {
        chartView.onEntitySelected = ::setPrice
    }

    private fun setPrice(chart: ChartEntity?) {
        val currentItem = item ?: return
        if (chart == null) {
            dateView.setText(Localization.price)
            priceView.text = currentItem.fiatPrice.withCustomSymbol(context)
            setDiffPrice(currentItem.rateNow.toFloat(), currentItem)
        } else {
            setData(chart.date, currentItem)
            setPrice(chart.price, currentItem)
        }
    }

    private fun setData(date: Long, item: Item.Chart) {
        if (0 >= date) {
            dateView.setText(Localization.price)
        } else {
            dateView.text = DateHelper.formatChartTime(date, context.locale, item.period == ChartPeriod.hour || item.period == ChartPeriod.day || item.period == ChartPeriod.week)
        }
    }

    private fun setPrice(price: Float, item: Item.Chart) {
        if (0 > price) {
            priceView.text = item.fiatPrice.withCustomSymbol(context)
        } else {
            val coins = Coins.of(price)
            priceView.text = CurrencyFormatter.formatFiat(item.currency.code, coins).withCustomSymbol(context)
        }
        setDiffPrice(price, item)
    }

    private fun setDiffPrice(price: Float, item: Item.Chart) {
        if (item.data.isEmpty()) {
            setDiffPrice(item.rateDiff24h.toString(), "")
        } else {
            val firstPrice = item.data.first().price
            val percent = (price - firstPrice) / firstPrice * 100
            val percentFormat = Formatter.percent(percent)

            val deltaPrice = Coins.of(price - firstPrice).abs()
            val deltaPriceFormat = CurrencyFormatter.formatFiat(item.currency.code, deltaPrice)

            setDiffPrice(percentFormat, deltaPriceFormat)
        }
    }

    override fun onBind(item: Item.Chart) {
        chartView.setData(item.data, item.square)
        periodView.selectedPeriod = item.period
        periodView.doOnPeriodSelected = chartPeriodCallback
        priceView.text = item.fiatPrice.withCustomSymbol(context)
        setDiffPrice(item.rateNow.toFloat(), item)
    }

    private fun setDiffPrice(percentFormat: String, fiatFormat: CharSequence) {
        val diffColor = context.getDiffColor(percentFormat)
        val builder = SpannableStringBuilder()
        builder.append(percentFormat)

        if (fiatFormat.isNotEmpty()) {
            builder.color(diffColor.withAlpha(.44f)) {
                append(" ")
                append(fiatFormat)
            }
        }

        diffView.text = builder
        diffView.setTextColor(diffColor)
    }

}