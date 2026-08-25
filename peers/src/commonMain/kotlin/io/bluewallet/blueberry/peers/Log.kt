package io.bluewallet.blueberry.peers

fun log(scope: String, message: String) {
    if (!logged(scope)) return
    println("blueberry[$scope] $message")
}

fun logError(scope: String, message: String, err: Throwable? = null) {
    if (!logged(scope)) return
    if (err == null) {
        println("blueberry[$scope] ERROR $message")
        return
    }
    println("blueberry[$scope] ERROR $message: ${err.message ?: err}")
    err.printStackTrace()
}

private fun logged(scope: String): Boolean = scope == "broadcast" || scope == "tor"
