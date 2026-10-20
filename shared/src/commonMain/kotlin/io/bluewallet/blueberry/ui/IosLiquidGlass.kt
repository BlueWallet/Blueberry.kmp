package io.bluewallet.blueberry.ui

/** Liquid glass controls shipped with iOS 26. [systemVersion] is `UIDevice.systemVersion`. */
internal fun supportsIosLiquidGlass(systemVersion: String): Boolean {
    val major = systemVersion.substringBefore('.').toIntOrNull() ?: return false
    return major >= 26
}
