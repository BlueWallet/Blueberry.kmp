package io.bluewallet.blueberry

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform
