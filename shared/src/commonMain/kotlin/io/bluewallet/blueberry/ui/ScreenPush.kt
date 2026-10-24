package io.bluewallet.blueberry.ui

import kotlin.math.max

internal const val SCREEN_PUSH_DURATION_MS = 250
internal const val SCREEN_PUSH_FADE_MS = 200

internal fun screenPushOffsetPx(fullWidthPx: Int): Int = max(0, fullWidthPx)
