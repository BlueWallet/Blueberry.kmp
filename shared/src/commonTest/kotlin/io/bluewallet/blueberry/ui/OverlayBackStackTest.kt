package io.bluewallet.blueberry.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class OverlayBackStackTest {
    @Test
    fun fire_runs_the_innermost_callback() {
        val stack = OverlayBackStack()
        val log = mutableListOf<String>()
        val outer: () -> Unit = { log += "outer" }
        val inner: () -> Unit = { log += "inner" }
        stack.push(outer)
        stack.push(inner)
        stack.fire()
        assertEquals(listOf("inner"), log)
    }

    @Test
    fun pop_inner_then_fire_runs_outer() {
        val stack = OverlayBackStack()
        val log = mutableListOf<String>()
        val outer: () -> Unit = { log += "outer" }
        val inner: () -> Unit = { log += "inner" }
        stack.push(outer)
        stack.push(inner)
        stack.pop(inner)
        stack.fire()
        assertEquals(listOf("outer"), log)
        assertEquals(1, stack.depth())
    }

    @Test
    fun fire_on_empty_is_noop() {
        OverlayBackStack().fire()
    }
}
