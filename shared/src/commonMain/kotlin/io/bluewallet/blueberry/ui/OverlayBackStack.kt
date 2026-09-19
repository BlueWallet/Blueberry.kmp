package io.bluewallet.blueberry.ui

internal class OverlayBackStack {
    private val stack = ArrayList<() -> Unit>()

    fun push(cb: () -> Unit) {
        stack.add(cb)
    }

    fun pop(cb: () -> Unit) {
        val i = stack.lastIndexOf(cb)
        if (i >= 0) stack.removeAt(i)
    }

    fun fire() {
        stack.lastOrNull()?.invoke()
    }

    internal fun depth(): Int = stack.size
}

internal val overlayBackStack = OverlayBackStack()
