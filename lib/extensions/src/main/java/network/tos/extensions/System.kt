package network.tos.extensions

fun currentTimeMillis(): Long {
    return System.currentTimeMillis()
}

fun currentTimeSeconds(): Long {
    return currentTimeMillis() / 1000
}
