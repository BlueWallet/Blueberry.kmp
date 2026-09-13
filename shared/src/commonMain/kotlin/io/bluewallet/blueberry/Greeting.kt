package io.bluewallet.blueberry

class Greeting {
    private val platform = getPlatform()

    fun greet(): String = sayHello(platform.name)
}
