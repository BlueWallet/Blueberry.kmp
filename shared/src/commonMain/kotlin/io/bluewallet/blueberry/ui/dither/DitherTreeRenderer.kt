package io.bluewallet.blueberry.ui.dither

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Colour ramp for the dither, darkest first, as ARGB ints. */
class DitherPalette(
    val colors: IntArray,
) {
    companion object {
        val Blueberry = ramp(0xFF05070D, 0xFF0D1630, 0xFF1C3878, 0xFF3F79CF, 0xFFA9C6F5)
        val Dusk = ramp(0xFF07060E, 0xFF17123A, 0xFF3A2F8F, 0xFF6E6BE0, 0xFFC9C3FA)
        val Ink = ramp(0xFF06070A, 0xFF141820, 0xFF2C3442, 0xFF6B7A92, 0xFFD9E1EE)

        private fun ramp(vararg argb: Long) = DitherPalette(IntArray(argb.size) { argb[it].toInt() })
    }
}

/** Where the tree stands and which side of the frame is darkened for text. */
enum class TreePlacement {
    /** Phone screens: tree at the bottom centre, top darkened. */
    BottomCenter,

    /** Wide screens: tree on the right, left side darkened. */
    BottomRight,
}

/**
 * Software renderer for the dithered blueberry tree.
 *
 * Pure Kotlin with no platform or Compose types, so it runs the same on
 * Android, iOS and desktop. The scene is drawn as luminance into a small
 * buffer (one cell per dithered pixel), then mapped to [DitherPalette]
 * through an 8×8 Bayer matrix with a travelling threshold wave.
 * The tree uses the same seed and generator as the website hero.
 */
@Suppress("TooManyFunctions")
class DitherTreeRenderer(
    seed: Int = 11,
) {
    var width = 0
        private set
    var height = 0
        private set

    private val tree: Branch
    private val stars: FloatArray
    private val motes: FloatArray
    private val leafCount: Int
    private val berryCount: Int

    private var lum = FloatArray(0)
    private var out = IntArray(0)
    private var scrim = FloatArray(0)
    private var waveSin = FloatArray(0)
    private var waveCos = FloatArray(0)
    private var leafBuf = FloatArray(0)
    private var berryBuf = FloatArray(0)
    private var leafLen = 0
    private var berryLen = 0

    private var vertical = true
    private var treeX = 0f
    private var baseY = 0f
    private var unit = 1f

    private var lightX = -0.55f
    private var lightY = -0.83f
    private var paint = 0f
    private var segX0 = 0f
    private var segY0 = 0f
    private var segX1 = 0f
    private var segY1 = 0f
    private var segAngle = 0f

    private var pointerX = Float.NaN
    private var pointerY = Float.NaN

    init {
        val rng = Mulberry32(seed)
        tree = buildBranch(rng, depth = 0, angle = 0f, length = 0.2f, width = 0.034f)
        stars = FloatArray(STAR_COUNT * 5) { i -> starField(rng, i % 5) }
        motes = FloatArray(MOTE_COUNT * 5) { i -> moteField(rng, i % 5) }
        leafCount = countLeaves(tree)
        berryCount = countBerries(tree)
    }

    /** Sizes the buffers. [width] and [height] are in dithered cells, not screen pixels. */
    fun resize(
        width: Int,
        height: Int,
        placement: TreePlacement,
    ) {
        require(width > 0 && height > 0) { "size must be positive" }
        this.width = width
        this.height = height
        lum = FloatArray(width * height)
        out = IntArray(width * height)
        waveSin = FloatArray(width)
        waveCos = FloatArray(width)
        leafBuf = FloatArray(leafCount * LEAF_STRIDE)
        berryBuf = FloatArray(berryCount * BERRY_STRIDE)
        vertical = placement == TreePlacement.BottomCenter
        if (vertical) {
            treeX = width * 0.5f
            // Portrait: lift the trunk, canopy, and moon so they clear bottom chrome.
            val trunk = 0.82f
            baseY = height * trunk - 2f
            unit = min(height * 0.62f, width * 1.15f)
            // The darkening was tuned for a trunk at the bottom. Shift it with the
            // trunk so the moon stays in the bright part of the ramp.
            val scrimLift = 1f - trunk
            scrim =
                FloatArray(height) { y ->
                    0.3f + 0.7f * smooth((y.toFloat() / height - (0.3f - scrimLift)) / 0.38f)
                }
        } else {
            treeX = width * 0.73f
            baseY = height * 0.94f
            unit = height * 0.92f
            scrim = FloatArray(width) { x -> 0.26f + 0.74f * smooth((x.toFloat() / width - 0.26f) / 0.36f) }
        }
    }

    /** Makes the light lean toward a point given as fractions of the frame. */
    fun setPointer(
        fx: Float,
        fy: Float,
    ) {
        pointerX = fx
        pointerY = fy
    }

    fun clearPointer() {
        pointerX = Float.NaN
        pointerY = Float.NaN
    }

    /**
     * Draws one frame and returns ARGB pixels, row-major, [width] × [height].
     * The returned array is reused by the next call.
     *
     * @param time seconds, drives sway, twinkle and the dither wave
     * @param growth 0..1, how far the tree has grown in
     * @param motion 0..1, scales all movement; 0 gives a still frame
     */
    fun render(
        time: Float,
        growth: Float,
        motion: Float,
        palette: DitherPalette,
    ): IntArray {
        check(width > 0) { "call resize() first" }
        updateLight(motion)
        drawBackdrop(time)
        leafLen = 0
        berryLen = 0
        val berryGrowth = clamp01((growth - 0.8f) / 0.2f)
        walk(tree, treeX, baseY + 0.01f * unit, -PI.toFloat() / 2f, Frame(time, growth, motion, berryGrowth))
        drawLeaves(time, motion)
        drawBerries()
        drawMotes(time)
        dither(palette, time, motion)
        return out
    }

    // ---------------------------------------------------------------- scene

    private class Frame(
        val time: Float,
        val growth: Float,
        val motion: Float,
        val berryGrowth: Float,
    )

    private fun canopyY() = baseY - 0.52f * unit

    private fun updateLight(motion: Float) {
        var tx = -0.55f
        var ty = -0.83f
        if (!pointerX.isNaN()) {
            val px = pointerX * width - treeX
            val py = pointerY * height - canopyY()
            val m = hypot(px, py).takeIf { it > 0f } ?: 1f
            tx = px / m
            ty = py / m
        }
        val ease = if (motion > 0f) 0.05f else 1f
        lightX += (tx - lightX) * ease
        lightY += (ty - lightY) * ease
        val m = hypot(lightX, lightY).takeIf { it > 0f } ?: 1f
        lightX /= m
        lightY /= m
    }

    private fun drawBackdrop(time: Float) {
        for (y in 0 until height) {
            val v = 0.015f + (0.11f - 0.015f) * y / (height - 1).coerceAtLeast(1)
            lum.fill(v, y * width, (y + 1) * width)
        }
        val hx = treeX + lightX * 0.2f * unit
        val hy = canopyY() + lightY * 0.2f * unit
        blendHalo(hx, hy, 0.78f * unit)
        for (i in 0 until STAR_COUNT) {
            val o = i * 5
            val twinkle = 0.55f + 0.45f * sin(time * stars[o + 3] + stars[o + 4])
            put((stars[o] * width).toInt(), (stars[o + 1] * height).toInt(), stars[o + 2] * twinkle)
        }
        val hillY = baseY + 0.92f * unit
        fillRect(0, (baseY + 0.09f * unit).toInt(), width, height, 0.035f)
        paint = 0.055f
        fillEllipse(treeX, hillY, 1.75f * unit, 0.92f * unit, 0f)
        strokeHillRim(treeX, hillY, 1.75f * unit, 0.92f * unit, 0.2f)
    }

    private fun walk(
        n: Branch,
        x: Float,
        y: Float,
        parentAngle: Float,
        f: Frame,
    ) {
        val progress = clamp01(f.growth * 8f - n.depth)
        if (progress <= 0f) return
        val sway = (sin(f.time * 0.9f + n.phase) * 0.016f + sin(f.time * 0.41f + n.depth) * 0.012f) * n.depth * f.motion
        val a = parentAngle + n.angle + sway
        val len = n.length * unit * progress
        val x2 = x + cos(a) * len
        val y2 = y + sin(a) * len
        val w = max(1f, n.width * unit * (0.6f + 0.4f * progress))
        paint = 0.12f
        strokeSegment(x, y, x2, y2, w)
        if (w > 2f) {
            val ox = lightX * w * 0.3f
            val oy = lightY * w * 0.3f
            paint = 0.28f
            strokeSegment(x + ox, y + oy, x2 + ox, y2 + oy, max(1f, w * 0.3f))
        }
        segX0 = x
        segY0 = y
        segX1 = x2
        segY1 = y2
        segAngle = a
        collectLeaves(n, f)
        collectBerries(n, f)
        for (k in n.kids) walk(k, x2, y2, a, f)
    }

    /** Uses the segment just drawn (segX0..segAngle). */
    private fun collectLeaves(
        n: Branch,
        f: Frame,
    ) {
        val progress = clamp01(f.growth * 8f - n.depth - 1f)
        if (progress <= 0f) return
        for (lf in n.leaves) {
            val o = leafLen
            leafBuf[o] = segX0 + (segX1 - segX0) * lf.t
            leafBuf[o + 1] = segY0 + (segY1 - segY0) * lf.t
            leafBuf[o + 2] = segAngle + lf.side * lf.rot + sin(f.time * 1.7f + lf.phase) * 0.14f * f.motion
            leafBuf[o + 3] = lf.size * unit * progress
            leafBuf[o + 4] = lf.phase
            leafLen += LEAF_STRIDE
        }
    }

    private fun collectBerries(
        n: Branch,
        f: Frame,
    ) {
        if (f.berryGrowth <= 0f) return
        val x2 = segX1
        val y2 = segY1
        for (b in n.berries) {
            val o = berryLen
            berryBuf[o] = x2 + b.ox * unit
            berryBuf[o + 1] = y2 + b.oy * unit + sin(f.time * 1.2f + b.phase) * 0.003f * unit * f.motion
            berryBuf[o + 2] = b.size * unit * f.berryGrowth
            berryBuf[o + 3] = x2
            berryBuf[o + 4] = y2
            berryLen += BERRY_STRIDE
        }
    }

    private fun drawLeaves(
        time: Float,
        motion: Float,
    ) {
        val cy = canopyY()
        var i = 0
        while (i < leafLen) {
            val px = leafBuf[i]
            val py = leafBuf[i + 1]
            val rot = leafBuf[i + 2]
            val s = leafBuf[i + 3]
            i += LEAF_STRIDE
            if (s < 0.5f) continue
            val dx = px - treeX
            val dy = py - cy
            val dm = hypot(dx, dy).takeIf { it > 0f } ?: 1f
            val lit = max(0f, (dx * lightX + dy * lightY) / dm)
            paint = 0.15f + 0.22f * lit + 0.05f * sin(time * 1.3f + leafBuf[i - 1]) * motion
            fillEllipse(px + cos(rot) * s * 0.7f, py + sin(rot) * s * 0.7f, s, s * 0.42f, rot)
        }
    }

    private fun drawBerries() {
        paint = 0.2f
        var i = 0
        while (i < berryLen) {
            strokeSegment(berryBuf[i + 3], berryBuf[i + 4], berryBuf[i], berryBuf[i + 1], 1f)
            i += BERRY_STRIDE
        }
        i = 0
        while (i < berryLen) {
            sphere(berryBuf[i], berryBuf[i + 1], berryBuf[i + 2])
            i += BERRY_STRIDE
        }
    }

    private fun drawMotes(time: Float) {
        for (i in 0 until MOTE_COUNT) {
            val o = i * 5
            val x = wrap01(motes[o] + time * motes[o + 2]) * width
            val y = wrap01(motes[o + 1] - time * motes[o + 3]) * height
            put(x.toInt(), y.toInt(), motes[o + 4])
        }
    }

    // ------------------------------------------------------------- dithering

    private fun dither(
        palette: DitherPalette,
        time: Float,
        motion: Float,
    ) {
        val colors = palette.colors
        val top = colors.size - 1
        val amp = 0.09f * motion
        for (x in 0 until width) {
            val k = x * 0.09f - time * 1.8f
            waveSin[x] = sin(k)
            waveCos[x] = cos(k)
        }
        for (y in 0 until height) {
            val row = (y and 7) shl 3
            val sb = sin(y * 0.05f)
            val cb = cos(y * 0.05f)
            val rowScrim = if (vertical) scrim[y] else 1f
            var i = y * width
            for (x in 0 until width) {
                val v = lum[i] * rowScrim * (if (vertical) 1f else scrim[x])
                val threshold = BAYER[row or (x and 7)] + amp * (waveSin[x] * cb + waveCos[x] * sb)
                val p = v * top
                var level = p.toInt()
                if (p - level > threshold) level++
                out[i] = colors[level.coerceIn(0, top)]
                i++
            }
        }
    }

    // ------------------------------------------------------------ rasterising

    private fun put(
        x: Int,
        y: Int,
        v: Float,
    ) {
        if (x in 0 until width && y in 0 until height) lum[y * width + x] = v
    }

    private fun fillRect(
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        v: Float,
    ) {
        for (y in max(0, y0) until min(height, y1)) {
            lum.fill(v, y * width + max(0, x0), y * width + min(width, x1))
        }
    }

    /** Canvas-style white radial glow: 0.46 at centre, 0.17 at 38%, 0 at [r]. */
    private fun blendHalo(
        cx: Float,
        cy: Float,
        r: Float,
    ) {
        for (y in cellFrom(cy - r, height)..cellTo(cy + r, height)) {
            for (x in cellFrom(cx - r, width)..cellTo(cx + r, width)) {
                val t = hypot(x + 0.5f - cx, y + 0.5f - cy) / r
                if (t >= 1f) continue
                val a = if (t < 0.38f) 0.46f - (0.46f - 0.17f) * t / 0.38f else 0.17f * (1f - t) / 0.62f
                val i = y * width + x
                lum[i] = a + (1f - a) * lum[i]
            }
        }
    }

    /** Filled, rotated ellipse in the current [paint]. */
    private fun fillEllipse(
        cx: Float,
        cy: Float,
        rx: Float,
        ry: Float,
        rot: Float,
    ) {
        if (rx <= 0f || ry <= 0f) return
        val r = max(rx, ry)
        val c = cos(rot)
        val s = sin(rot)
        for (y in cellFrom(cy - r, height)..cellTo(cy + r, height)) {
            val dy = y + 0.5f - cy
            for (x in cellFrom(cx - r, width)..cellTo(cx + r, width)) {
                val dx = x + 0.5f - cx
                val u = (dx * c + dy * s) / rx
                val w = (-dx * s + dy * c) / ry
                if (u * u + w * w <= 1f) lum[y * width + x] = paint
            }
        }
    }

    /** Top arc of the hill outline, about 61° either side of vertical. */
    private fun strokeHillRim(
        cx: Float,
        cy: Float,
        rx: Float,
        ry: Float,
        v: Float,
    ) {
        val reach = rx * cos(0.5f)
        for (x in cellFrom(cx - reach, width)..cellTo(cx + reach, width)) {
            val d = (x + 0.5f - cx) / rx
            put(x, floor(cy - ry * sqrt(max(0f, 1f - d * d))).toInt(), v)
        }
    }

    /** A thick segment with round caps, in the current [paint]. */
    private fun strokeSegment(
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        lineWidth: Float,
    ) {
        val hw = max(0.6f, lineWidth / 2f)
        val dx = x1 - x0
        val dy = y1 - y0
        val len2 = dx * dx + dy * dy
        for (y in cellFrom(min(y0, y1) - hw, height)..cellTo(max(y0, y1) + hw, height)) {
            for (x in cellFrom(min(x0, x1) - hw, width)..cellTo(max(x0, x1) + hw, width)) {
                val px = x + 0.5f - x0
                val py = y + 0.5f - y0
                val t = if (len2 > 0f) clamp01((px * dx + py * dy) / len2) else 0f
                val ex = px - dx * t
                val ey = py - dy * t
                if (ex * ex + ey * ey <= hw * hw) lum[y * width + x] = paint
            }
        }
    }

    /**
     * A glossy berry: the canvas two-circle radial gradient from a highlight
     * point (radius 0) to the berry outline (radius [r]).
     */
    private fun sphere(
        x: Float,
        y: Float,
        r: Float,
    ) {
        if (r < 0.6f) return
        val gx = x + lightX * r * 0.45f
        val gy = y + lightY * r * 0.45f
        val dx = x - gx
        val dy = y - gy
        val a = dx * dx + dy * dy - r * r
        for (py in cellFrom(y - r, height)..cellTo(y + r, height)) {
            for (px in cellFrom(x - r, width)..cellTo(x + r, width)) {
                val cx = px + 0.5f - x
                val cy = py + 0.5f - y
                if (cx * cx + cy * cy > r * r) continue
                val qx = px + 0.5f - gx
                val qy = py + 0.5f - gy
                val qd = qx * dx + qy * dy
                val disc = qd * qd - a * (qx * qx + qy * qy)
                val t = if (abs(a) < 1e-6f) 1f else clamp01((qd - sqrt(max(0f, disc))) / a)
                lum[py * width + px] = berryShade(t)
            }
        }
    }

    private fun berryShade(t: Float): Float =
        when {
            t < 0.28f -> 1f - 0.2f * t / 0.28f
            t < 0.75f -> 0.8f - 0.24f * (t - 0.28f) / 0.47f
            else -> 0.56f - 0.2f * (t - 0.75f) / 0.25f
        }

    private fun cellFrom(
        lo: Float,
        limit: Int,
    ) = floor(lo).toInt().coerceIn(0, limit)

    private fun cellTo(
        hi: Float,
        limit: Int,
    ) = (ceil(hi).toInt()).coerceIn(-1, limit - 1)

    // ----------------------------------------------------------- generation

    private class Branch(
        val depth: Int,
        val angle: Float,
        val length: Float,
        val width: Float,
        val phase: Float,
    ) {
        val kids = ArrayList<Branch>(3)
        val leaves = ArrayList<Leaf>(5)
        val berries = ArrayList<Berry>(6)
    }

    private class Leaf(
        val t: Float,
        val side: Float,
        val rot: Float,
        val size: Float,
        val phase: Float,
    )

    private class Berry(
        val ox: Float,
        val oy: Float,
        val size: Float,
        val phase: Float,
    )

    /** Same draw order as the website's JS, so both make the same tree. */
    private fun buildBranch(
        rng: Mulberry32,
        depth: Int,
        angle: Float,
        length: Float,
        width: Float,
    ): Branch {
        val n = Branch(depth, angle, length, width, rng.next() * TAU)
        if (depth < MAX_DEPTH) {
            val count =
                when {
                    depth == 0 -> 3
                    rng.next() < 0.3f -> 3
                    else -> 2
                }
            val spread = if (depth == 0) 0.62f else 0.48f
            for (i in 0 until count) {
                val childAngle = (i - (count - 1) / 2f) * (spread + rng.next() * 0.22f) + (rng.next() - 0.5f) * 0.28f
                val childLength = length * (0.7f + rng.next() * 0.12f)
                n.kids.add(buildBranch(rng, depth + 1, childAngle, childLength, width * 0.64f))
            }
        }
        if (depth >= 3) {
            repeat(if (depth >= 5) 5 else 3) {
                n.leaves.add(Leaf(0.3f + rng.next() * 0.7f, if (rng.next() < 0.5f) -1f else 1f, 0.45f + rng.next() * 0.7f, 0.017f + rng.next() * 0.013f, rng.next() * TAU))
            }
        }
        if (depth == MAX_DEPTH && rng.next() < 0.4f) {
            repeat(3 + floor(rng.next() * 4f).toInt()) {
                n.berries.add(Berry((rng.next() - 0.5f) * 0.05f, 0.012f + rng.next() * 0.034f, 0.009f + rng.next() * 0.006f, rng.next() * TAU))
            }
        }
        return n
    }

    private fun countLeaves(n: Branch): Int = n.leaves.size + n.kids.sumOf { countLeaves(it) }

    private fun countBerries(n: Branch): Int = n.berries.size + n.kids.sumOf { countBerries(it) }

    /** Fields per star: x, y (top 70%), brightness, twinkle speed, phase. */
    private fun starField(
        rng: Mulberry32,
        field: Int,
    ): Float =
        when (field) {
            1 -> rng.next() * 0.7f
            2 -> 0.35f + rng.next() * 0.5f
            3 -> 0.6f + rng.next() * 2.2f
            4 -> rng.next() * TAU
            else -> rng.next()
        }

    /** Fields per mote: x, y, drift x, rise speed, brightness. */
    private fun moteField(
        rng: Mulberry32,
        field: Int,
    ): Float =
        when (field) {
            2 -> (rng.next() - 0.5f) * 0.006f
            3 -> 0.004f + rng.next() * 0.012f
            4 -> 0.4f + rng.next() * 0.35f
            else -> rng.next()
        }

    /** The website's mulberry32, bit for bit (JS Math.imul is Int multiply here). */
    private class Mulberry32(
        seed: Int,
    ) {
        private var s = seed

        fun next(): Float {
            s += 0x6D2B79F5
            var t = s
            t = (t xor (t ushr 15)) * (t or 1)
            t = t xor (t + (t xor (t ushr 7)) * (t or 61))
            return ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL).toFloat() / 4294967296f
        }
    }

    private companion object {
        const val MAX_DEPTH = 6
        const val STAR_COUNT = 110
        const val MOTE_COUNT = 46
        const val LEAF_STRIDE = 5
        const val BERRY_STRIDE = 5
        const val TAU = 6.2831855f

        val BAYER: FloatArray =
            intArrayOf(
                0,
                32,
                8,
                40,
                2,
                34,
                10,
                42,
                48,
                16,
                56,
                24,
                50,
                18,
                58,
                26,
                12,
                44,
                4,
                36,
                14,
                46,
                6,
                38,
                60,
                28,
                52,
                20,
                62,
                30,
                54,
                22,
                3,
                35,
                11,
                43,
                1,
                33,
                9,
                41,
                51,
                19,
                59,
                27,
                49,
                17,
                57,
                25,
                15,
                47,
                7,
                39,
                13,
                45,
                5,
                37,
                63,
                31,
                55,
                23,
                61,
                29,
                53,
                21,
            ).let { m -> FloatArray(64) { (m[it] + 0.5f) / 64f } }

        fun clamp01(v: Float) = v.coerceIn(0f, 1f)

        fun smooth(v: Float): Float {
            val k = clamp01(v)
            return k * k * (3f - 2f * k)
        }

        fun wrap01(v: Float): Float = v - floor(v)
    }
}
