package network.tos.extensions

fun Long?.isPositive(): Boolean {
    if (this == null) return false
    return this > 0
}