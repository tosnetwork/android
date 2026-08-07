package network.tos.icu

object Formatter {

    fun percent(value: Float): String {
        val format = if (value == 0f) {
            "%.2f%%"
        } else if (value > 0) {
            "+%.2f%%"
        } else {
            "%.2f%%"
        }
        return format.format(value)
    }
}