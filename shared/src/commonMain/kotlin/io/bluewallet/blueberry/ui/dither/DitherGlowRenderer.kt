package io.bluewallet.blueberry.ui.dither

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The subtle loading glow: the hero's dither (same Bayer matrix, shimmer wave
 * and palette family) without the tree. A soft band that is brightest at the
 * bottom edge, lit by three halos drifting sideways, with a faint pulse rising
 * every 2.8 s and a few specks floating up.
 *
 * Pure Kotlin. Level 0 of the palette should be transparent so the screen
 * shows through, see [DitherPalette.GlowDark].
 */
class DitherGlowRenderer(
    seed: Int = 7,
) {
    var width = 0
        private set
    var height = 0
        private set

    private val halos: FloatArray
    private val motes: FloatArray

    private var lum = FloatArray(0)
    private var out = IntArray(0)
    private var waveSin = FloatArray(0)
    private var waveCos = FloatArray(0)
    private var haloX = FloatArray(HALO_COUNT)

    init {
        val rng = Mulberry32(seed)
        halos = FloatArray(HALO_COUNT * 3) { i -> haloField(rng, i % 3) }
        motes = FloatArray(MOTE_COUNT * 4) { i -> moteField(rng, i % 4) }
    }

    /** Sizes the buffers, in dithered cells. */
    fun resize(
        width: Int,
        height: Int,
    ) {
        require(width > 0 && height > 0) { "size must be positive" }
        this.width = width
        this.height = height
        lum = FloatArray(width * height)
        out = IntArray(width * height)
        waveSin = FloatArray(width)
        waveCos = FloatArray(width)
    }

    /**
     * Draws one frame and returns ARGB pixels, row-major. The array is reused.
     *
     * @param time seconds
     * @param intensity 0..1, fade in/out; 0 gives a fully transparent frame
     * @param motion 0..1, scales all movement; 0 gives a still band
     */
    fun render(
        time: Float,
        intensity: Float,
        motion: Float,
        palette: DitherPalette,
    ): IntArray {
        check(width > 0) { "call resize() first" }
        val t = if (motion > 0f) time else 0f
        fillBand(t, intensity, motion)
        if (motion > 0f) drawMotes(t, intensity)
        dither(palette.colors, t, motion)
        return out
    }

    private fun fillBand(
        t: Float,
        intensity: Float,
        motion: Float,
    ) {
        for (h in 0 until HALO_COUNT) {
            haloX[h] = 0.5f + 0.42f * sin(t * halos[h * 3] + halos[h * 3 + 1])
        }
        val phase = if (motion > 0f) (t % PULSE_SECONDS) / PULSE_SECONDS else -1f
        val center = 1.08f - phase * 1.15f
        val rows = max(height - 1, 1).toFloat()
        val cols = max(width - 1, 1).toFloat()
        for (y in 0 until height) {
            val yn = y / rows
            val shade = glowTopFade(yn)
            val dy = yn - 1.12f
            val ey = exp(-(dy * dy) / (2f * 0.36f * 0.36f))
            val pulse = if (phase >= 0f) pulseAt(yn, center, phase) else 0f
            var i = y * width
            for (x in 0 until width) {
                val g = haloSum(x / cols)
                lum[i] = intensity * shade * (0.24f + 0.14f * g * ey + pulse)
                i++
            }
        }
    }

    private fun pulseAt(
        yn: Float,
        center: Float,
        phase: Float,
    ): Float {
        val d = yn - center
        return 0.06f * exp(-(d * d) / 0.004f) * yn.pow(0.8f) * (1f - phase)
    }

    private fun haloSum(xn: Float): Float {
        var g = 0f
        for (h in 0 until HALO_COUNT) {
            val d = xn - haloX[h]
            g += halos[h * 3 + 2] * exp(-d * d * HALO_K)
        }
        return g
    }

    private fun drawMotes(
        t: Float,
        intensity: Float,
    ) {
        for (m in 0 until MOTE_COUNT) {
            val o = m * 4
            val k = (t * motes[o + 1] + motes[o + 2]).let { it - floor(it) }
            val yn = 1f - k * 0.85f
            val x = (motes[o] * width).toInt()
            val y = (yn * (height - 1)).toInt()
            if (x in 0 until width && y in 0 until height) {
                val i = y * width + x
                lum[i] = max(lum[i], intensity * glowTopFade(yn) * motes[o + 3] * yn)
            }
        }
    }

    private fun dither(
        colors: IntArray,
        t: Float,
        motion: Float,
    ) {
        val top = colors.size - 1
        val amp = 0.09f * motion
        for (x in 0 until width) {
            val k = x * 0.09f - t * 1.8f
            waveSin[x] = sin(k)
            waveCos[x] = cos(k)
        }
        for (y in 0 until height) {
            val row = (y and 7) shl 3
            val sb = sin(y * 0.05f)
            val cb = cos(y * 0.05f)
            var i = y * width
            for (x in 0 until width) {
                out[i] = dithered(lum[i], x, row, cb, sb, amp, top, colors)
                i++
            }
        }
    }

    @Suppress("LongParameterList")
    private fun dithered(
        v: Float,
        x: Int,
        row: Int,
        cb: Float,
        sb: Float,
        amp: Float,
        top: Int,
        colors: IntArray,
    ): Int {
        if (v < CUTOFF) return 0
        val wave = amp * min(1f, v * 5f) * (waveSin[x] * cb + waveCos[x] * sb)
        val p = v * top
        var level = p.toInt()
        if (p - level > BAYER_8X8[row or (x and 7)] + wave) level++
        return colors[level.coerceIn(0, top)]
    }

    /** Per halo: drift speed, phase, strength. */
    private fun haloField(
        rng: Mulberry32,
        field: Int,
    ): Float =
        when (field) {
            0 -> 0.13f + rng.next() * 0.17f
            1 -> rng.next() * TAU
            else -> 0.7f + rng.next() * 0.5f
        }

    /** Per mote: x, rise speed, phase, brightness. */
    private fun moteField(
        rng: Mulberry32,
        field: Int,
    ): Float =
        when (field) {
            1 -> 0.05f + rng.next() * 0.09f
            3 -> 0.5f + rng.next() * 0.35f
            else -> rng.next()
        }

    private companion object {
        const val HALO_COUNT = 3
        const val MOTE_COUNT = 18
        const val PULSE_SECONDS = 2.8f
        const val CUTOFF = 0.006f
        const val TAU = 6.2831855f
        const val HALO_K = 1f / (2f * 0.2f * 0.2f)
    }
}

/**
 * Long ease into black. Full strength at the bottom edge, thinning out
 * over most of the band so the top does not end in a hard line of dots.
 */
private fun glowTopFade(yn: Float): Float = yn.coerceIn(0f, 1f).pow(1.45f)
