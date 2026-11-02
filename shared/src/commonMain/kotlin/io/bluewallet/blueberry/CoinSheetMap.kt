@file:Suppress(
    "TooManyFunctions",
    "LongMethod",
    "LongParameterList",
    "CyclomaticComplexMethod",
    "ComplexCondition",
    "NestedBlockDepth",
    "LoopWithTooManyJumpStatements",
    "ReturnCount",
)

package io.bluewallet.blueberry

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

const val COIN_MAP_WIDTH = 326f
const val COIN_MAP_HEIGHT = 200f

/** Where the open coin's group is centered, as a fraction of the map width. */
const val COIN_MAP_OPEN_X = 0.5f

data class MapCoin(
    val key: String,
    val valueSats: Long,
    val colorArgb: Long,
    val frozen: Boolean,
    val groupId: String,
    val groupTitle: String?,
    val title: String,
)

enum class MapRole {
    OpenGroup,
    OtherGroup,
    Separate,
}

data class MapDot(
    val key: String,
    val x: Float,
    val y: Float,
    val diameter: Float,
    val colorArgb: Long,
    val frozen: Boolean,
    val open: Boolean,
    val selected: Boolean,
    val role: MapRole,
)

data class MapSegment(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val selected: Boolean,
    val openGroup: Boolean,
    val memberKey: String,
)

data class MapOutline(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val openGroup: Boolean,
)

data class MapTag(
    val text: String,
    val x: Float,
    val y: Float,
    val openGroup: Boolean,
)

data class CoinMapLayout(
    val width: Float,
    val height: Float,
    val dots: List<MapDot>,
    val segments: List<MapSegment>,
    val outlines: List<MapOutline>,
    val tags: List<MapTag>,
    val overflow: Int,
    val description: String,
)

private data class Cluster(
    val id: String,
    val title: String?,
    val open: Boolean,
    val linked: Boolean,
    val centerKey: String,
    val memberKeys: List<String>,
)

private data class Spot(
    val key: String,
    val x: Float,
    val y: Float,
    val diameter: Float,
)

private data class Box(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/** Deterministic picture of every unspent coin. Same coins and link order always land in the same place. */
fun layoutCoinMap(
    coins: List<MapCoin>,
    openKey: String,
    selectedKey: String?,
    linkOrder: List<String>,
): CoinMapLayout {
    val description = describeMap(coins, openKey)
    var remaining = coins
    var dropped = 0
    while (remaining.map { it.groupId }.distinct().size > MAX_CLUSTERS) {
        val drop = nextToDrop(remaining, openKey) ?: break
        remaining = remaining.filter { it.key !in drop }
        dropped += drop.size
    }
    while (true) {
        // Prefer full size: move the open group first, shrink a little, then leave coins off.
        for (scale in SCALES) {
            val placed = arrange(remaining, openKey, linkOrder, scale)
            if (placed != null) {
                return layoutOf(placed, openKey, selectedKey, remaining, dropped, description)
            }
        }
        val drop = nextToDrop(remaining, openKey) ?: break
        remaining = remaining.filter { it.key !in drop }
        dropped += drop.size
    }
    val forced = arrange(remaining, openKey, linkOrder, SCALES.last(), force = true).orEmpty()
    return layoutOf(forced, openKey, selectedKey, remaining, dropped, description)
}

/** Same positions with [selectedKey] highlighted. Cheap: selection never moves anything. */
fun CoinMapLayout.withSelection(selectedKey: String?): CoinMapLayout =
    copy(
        dots = dots.map { it.copy(selected = it.key == selectedKey) },
        segments = segments.map { it.copy(selected = it.openGroup && it.memberKey == selectedKey) },
    )

private fun arrange(
    coins: List<MapCoin>,
    openKey: String,
    linkOrder: List<String>,
    scale: Float,
    force: Boolean = false,
): List<Spot>? {
    val sizes = diameters(coins, scale)
    val gap = RING_GAP * scale
    val clusters = clustersOf(coins, openKey, linkOrder)
    val open = clusters.find { it.open } ?: return if (force) emptyList() else null
    val others = clusters.filter { !it.open }
    for ((dx, dy) in OPEN_NUDGES) {
        val center = COIN_MAP_WIDTH * COIN_MAP_OPEN_X + dx to COIN_MAP_HEIGHT * 0.5f + dy
        val openSpots = spotsFor(open, sizes, gap, center.first, center.second)
        val openBox = footprint(open, openSpots)
        if (!inside(openBox)) continue
        val rest = placeAround(others, sizes, gap, center, openBox, force = false)
        if (rest != null) return openSpots + rest
    }
    if (!force) return null
    val center = COIN_MAP_WIDTH * COIN_MAP_OPEN_X to COIN_MAP_HEIGHT * 0.5f
    val openSpots = spotsFor(open, sizes, gap, center.first, center.second)
    return openSpots + placeAround(others, sizes, gap, center, footprint(open, openSpots), force = true).orEmpty()
}

private fun placeAround(
    others: List<Cluster>,
    sizes: Map<String, Float>,
    gap: Float,
    openCenter: Pair<Float, Float>,
    openBox: Box,
    force: Boolean,
): List<Spot>? {
    val placed = mutableListOf<Spot>()
    val boxes = mutableListOf(openBox)
    others.forEachIndexed { index, cluster ->
        val target = ringTarget(index, openCenter)
        val spot = searchCenter(cluster, sizes, gap, boxes, target, force)
        if (spot == null && !force) return null
        val center = spot ?: target
        val spots = spotsFor(cluster, sizes, gap, center.first, center.second)
        placed += spots
        boxes += footprint(cluster, spots)
    }
    return placed
}

/**
 * Space a cluster claims: its dots, plus the outline and the name tag below it for linked
 * groups, or the "This coin" tag above a lone open coin. Clusters never overlap footprints.
 */
private fun footprint(
    cluster: Cluster,
    spots: List<Spot>,
): Box {
    val dots = boxOf(spots)
    return when {
        cluster.linked ->
            Box(dots.left - OUTLINE_PAD, dots.top - OUTLINE_PAD, dots.right + OUTLINE_PAD, dots.bottom + OUTLINE_PAD + TAG_SPACE)
                .widenedTo(GROUP_TAG_WIDTH)
        cluster.open -> dots.copy(top = dots.top - TAG_SPACE).widenedTo(COIN_TAG_WIDTH)
        else -> dots
    }
}

private fun Box.widenedTo(width: Float): Box {
    val missing = (width - (right - left)).coerceAtLeast(0f) / 2f
    return copy(left = left - missing, right = right + missing)
}

/** Where the n-th other cluster would like to sit: golden-angle steps around the open group, larger coins first. */
private fun ringTarget(
    index: Int,
    around: Pair<Float, Float>,
): Pair<Float, Float> {
    val radians = index * GOLDEN_ANGLE * PI / 180.0
    val reach = 1f + index * RING_GROWTH
    val x = around.first + COIN_MAP_WIDTH * RING_REACH_X * reach * cos(radians).toFloat()
    val y = around.second + COIN_MAP_HEIGHT * RING_REACH_Y * reach * sin(radians).toFloat()
    return x.coerceIn(EDGE, COIN_MAP_WIDTH - EDGE) to y.coerceIn(EDGE, COIN_MAP_HEIGHT - EDGE)
}

private fun layoutOf(
    spots: List<Spot>,
    openKey: String,
    selectedKey: String?,
    coins: List<MapCoin>,
    overflow: Int,
    description: String,
): CoinMapLayout {
    val byKey = coins.associateBy { it.key }
    val openGroup = byKey[openKey]?.groupId
    val dots =
        spots.mapNotNull { spot ->
            val coin = byKey[spot.key] ?: return@mapNotNull null
            val linked = coins.count { it.groupId == coin.groupId } >= 2
            val role =
                when {
                    coin.groupId == openGroup -> MapRole.OpenGroup
                    linked -> MapRole.OtherGroup
                    else -> MapRole.Separate
                }
            MapDot(
                key = spot.key,
                x = spot.x,
                y = spot.y,
                diameter = spot.diameter,
                colorArgb = coin.colorArgb,
                frozen = coin.frozen,
                open = spot.key == openKey,
                selected = spot.key == selectedKey,
                role = role,
            )
        }
    return CoinMapLayout(
        width = COIN_MAP_WIDTH,
        height = COIN_MAP_HEIGHT,
        dots = dots,
        segments = segmentsOf(dots, coins, openKey, selectedKey),
        outlines = outlinesOf(dots, coins, openKey),
        tags = tagsOf(dots, coins, openKey),
        overflow = overflow,
        description = description,
    )
}

private fun diameters(
    coins: List<MapCoin>,
    scale: Float,
): Map<String, Float> {
    val largest = coins.maxOfOrNull { it.valueSats } ?: 0L
    return coins.associate { coin ->
        val ratio = if (largest <= 0L) 0.0 else sqrt(coin.valueSats.toDouble() / largest.toDouble())
        coin.key to ((10f + 34f * ratio.toFloat()) * scale).coerceAtLeast(6f)
    }
}

private fun clustersOf(
    coins: List<MapCoin>,
    openKey: String,
    linkOrder: List<String>,
): List<Cluster> {
    val groups = coins.groupBy { it.groupId }
    val clusters =
        groups.map { (id, members) ->
            val linked = members.size >= 2
            val isOpen = members.any { it.key == openKey }
            val center =
                if (isOpen) {
                    openKey
                } else {
                    members.maxWith(compareBy<MapCoin> { it.valueSats }.thenByDescending { it.key }).key
                }
            val ring =
                if (isOpen) {
                    val ordered = linkOrder.filter { key -> members.any { it.key == key } }
                    val rest = members.map { it.key }.filter { it != openKey && it !in ordered }.sorted()
                    ordered + rest
                } else {
                    members.filter { it.key != center }.sortedByDescending { it.valueSats }.map { it.key }
                }
            Cluster(id, members.firstNotNullOfOrNull { it.groupTitle }, isOpen, linked, center, ring)
        }
    val openCluster = clusters.filter { it.open }
    val rest =
        clusters
            .filter { !it.open }
            .sortedWith(compareByDescending<Cluster> { groups.getValue(it.id).maxOf { coin -> coin.valueSats } }.thenBy { it.id })
    return openCluster + rest
}

private fun spotsFor(
    cluster: Cluster,
    sizes: Map<String, Float>,
    gap: Float,
    cx: Float,
    cy: Float,
): List<Spot> {
    val centerSize = sizes[cluster.centerKey] ?: 10f
    val spots = mutableListOf(Spot(cluster.centerKey, cx, cy, centerSize))
    val members = cluster.memberKeys
    // Members sit on a wide ellipse, first one to the right, so groups read left to right.
    members.forEachIndexed { index, key ->
        val angle = 360.0 * index / members.size
        val radians = angle * PI / 180.0
        val memberSize = sizes[key] ?: 10f
        val distance = centerSize / 2f + memberSize / 2f + gap
        spots +=
            Spot(
                key,
                cx + (distance * RING_STRETCH_X * cos(radians)).toFloat(),
                cy + (distance * RING_SQUASH_Y * sin(radians)).toFloat(),
                memberSize,
            )
    }
    return spots
}

private fun searchCenter(
    cluster: Cluster,
    sizes: Map<String, Float>,
    gap: Float,
    taken: List<Box>,
    target: Pair<Float, Float>,
    force: Boolean,
): Pair<Float, Float>? {
    var best: Pair<Float, Float>? = null
    var bestDistance = Float.MAX_VALUE
    // Grid anchored on the target so the target itself is a candidate.
    val stepsX = (COIN_MAP_WIDTH / GRID).toInt() + 1
    val stepsY = (COIN_MAP_HEIGHT / GRID).toInt() + 1
    for (ix in -stepsX..stepsX) {
        val x = target.first + ix * GRID
        if (x < EDGE || x > COIN_MAP_WIDTH - EDGE) continue
        for (iy in -stepsY..stepsY) {
            val y = target.second + iy * GRID
            if (y < EDGE || y > COIN_MAP_HEIGHT - EDGE) continue
            val box = footprint(cluster, spotsFor(cluster, sizes, gap, x, y))
            val clear = inside(box) && taken.none { overlaps(box, it) }
            val distance = (x - target.first) * (x - target.first) + (y - target.second) * (y - target.second)
            if (clear && (best == null || distance < bestDistance || (distance == bestDistance && closer(x, y, best)))) {
                best = x to y
                bestDistance = distance
            }
        }
    }
    return if (best == null && force) target else best
}

private fun closer(
    x: Float,
    y: Float,
    best: Pair<Float, Float>,
): Boolean = x < best.first || (x == best.first && y < best.second)

private fun boxOf(spots: List<Spot>): Box {
    var left = Float.MAX_VALUE
    var top = Float.MAX_VALUE
    var right = -Float.MAX_VALUE
    var bottom = -Float.MAX_VALUE
    for (spot in spots) {
        val radius = spot.diameter / 2f
        left = minOf(left, spot.x - radius)
        top = minOf(top, spot.y - radius)
        right = maxOf(right, spot.x + radius)
        bottom = maxOf(bottom, spot.y + radius)
    }
    return Box(left, top, right, bottom)
}

private fun inside(box: Box): Boolean = box.left >= EDGE && box.top >= EDGE && box.right <= COIN_MAP_WIDTH - EDGE && box.bottom <= COIN_MAP_HEIGHT - EDGE

private fun overlaps(
    a: Box,
    b: Box,
): Boolean = a.left < b.right + GAP && a.right + GAP > b.left && a.top < b.bottom + GAP && a.bottom + GAP > b.top

/**
 * What to leave off when the map is full: the smallest lone coin, then the smallest other
 * linked group as a whole. Never part of a group, never the open coin's group. Null when only
 * the open group is left.
 */
private fun nextToDrop(
    coins: List<MapCoin>,
    openKey: String,
): Set<String>? {
    val openGroup = coins.find { it.key == openKey }?.groupId
    val groups = coins.filter { it.groupId != openGroup }.groupBy { it.groupId }
    val lone =
        groups.values
            .filter { it.size == 1 }
            .map { it.single() }
            .minWithOrNull(compareBy<MapCoin> { it.valueSats }.thenBy { it.key })
    if (lone != null) return setOf(lone.key)
    return groups.values
        .minWithOrNull(compareBy<List<MapCoin>> { members -> members.sumOf { it.valueSats } }.thenBy { it.first().groupId })
        ?.map { it.key }
        ?.toSet()
}

private fun segmentsOf(
    dots: List<MapDot>,
    coins: List<MapCoin>,
    openKey: String,
    selectedKey: String?,
): List<MapSegment> {
    val byKey = dots.associateBy { it.key }
    val openGroup = coins.find { it.key == openKey }?.groupId
    val segments = mutableListOf<MapSegment>()
    for ((id, members) in coins.groupBy { it.groupId }) {
        val centerKey = if (id == openGroup) openKey else members.maxByOrNull { it.valueSats }?.key
        val center = centerKey?.let { byKey[it] } ?: continue
        for (member in members) {
            val dot = byKey[member.key]
            if (dot != null && dot.key != center.key) {
                segments +=
                    MapSegment(
                        center.x,
                        center.y,
                        dot.x,
                        dot.y,
                        selected = id == openGroup && member.key == selectedKey,
                        openGroup = id == openGroup,
                        memberKey = member.key,
                    )
            }
        }
    }
    return segments
}

private fun outlinesOf(
    dots: List<MapDot>,
    coins: List<MapCoin>,
    openKey: String,
): List<MapOutline> {
    val openGroup = coins.find { it.key == openKey }?.groupId
    val outlines = mutableListOf<MapOutline>()
    for ((id, members) in coins.groupBy { it.groupId }) {
        val drawn = dots.filter { dot -> members.any { it.key == dot.key } }
        if (members.size >= 2 && drawn.isNotEmpty()) {
            val box = boxOf(drawn.map { Spot(it.key, it.x, it.y, it.diameter) })
            outlines +=
                MapOutline(
                    box.left - OUTLINE_PAD,
                    box.top - OUTLINE_PAD,
                    box.right + OUTLINE_PAD,
                    box.bottom + OUTLINE_PAD,
                    openGroup = id == openGroup,
                )
        }
    }
    return outlines
}

private fun tagsOf(
    dots: List<MapDot>,
    coins: List<MapCoin>,
    openKey: String,
): List<MapTag> {
    val tags = mutableListOf<MapTag>()
    val open = dots.find { it.key == openKey }
    val openCoin = coins.find { it.key == openKey }
    if (open != null && openCoin != null && coins.count { it.groupId == openCoin.groupId } == 1) {
        tags += MapTag("This coin", open.x, open.y - open.diameter / 2f - 14f, openGroup = true)
    }
    for (outline in outlinesOf(dots, coins, openKey)) {
        val title =
            coins
                .firstOrNull { coin ->
                    val dot = dots.find { it.key == coin.key } ?: return@firstOrNull false
                    dot.x >= outline.left && dot.x <= outline.right && dot.y >= outline.top && dot.y <= outline.bottom
                }?.groupTitle
        if (title != null) tags += MapTag(title, (outline.left + outline.right) / 2f, outline.bottom + 14f, outline.openGroup)
    }
    return tags
}

private fun describeMap(
    coins: List<MapCoin>,
    openKey: String,
): String {
    val open = coins.find { it.key == openKey }
    val noun = if (coins.size == 1) "coin" else "coins"
    val head = "Map of your ${coins.size} $noun."
    if (open == null) return head
    val companions = coins.filter { it.groupId == open.groupId && it.key != open.key }
    val otherGroups = coins.filter { it.groupId != open.groupId }.groupBy { it.groupId }.count { it.value.size >= 2 }
    val lones = coins.count { coin -> coin.key != open.key && coins.count { it.groupId == coin.groupId } == 1 }
    val separate = separatePhrase(otherGroups, lones)
    return if (companions.isEmpty()) {
        "$head This coin is solo. $separate"
    } else {
        val names = companions.joinToString(", ") { it.title }
        "$head This coin is in ${open.groupTitle ?: "a group"} with $names. $separate"
    }
}

private fun separatePhrase(
    groups: Int,
    lones: Int,
): String {
    val groupPart = if (groups == 1) "1 group" else "$groups groups"
    val soloPart = if (lones == 1) "1 solo coin" else "$lones solo coins"
    return when {
        groups == 0 && lones == 0 -> "Nothing else is separate."
        groups == 0 -> "$soloPart ${if (lones == 1) "is" else "are"} separate."
        lones == 0 -> "$groupPart ${if (groups == 1) "is" else "are"} separate."
        else -> "$groupPart and $soloPart are separate."
    }
}

private const val EDGE = 6f
private const val GAP = 10f
private const val GRID = 8f
private const val OUTLINE_PAD = 14f
private const val RING_STRETCH_X = 1.2f
private const val RING_SQUASH_Y = 0.8f
private const val TAG_SPACE = 24f
private const val GROUP_TAG_WIDTH = 100f
private const val COIN_TAG_WIDTH = 64f
private const val GOLDEN_ANGLE = 137.508
private const val RING_REACH_X = 0.3f
private const val RING_REACH_Y = 0.3f
private const val RING_GROWTH = 0.06f
private const val RING_GAP = 24f
private const val MAX_CLUSTERS = 14
private val SCALES = listOf(1f, 0.85f, 0.72f)

/** Offsets tried for the open group, nearest the middle first. */
private val OPEN_NUDGES: List<Pair<Float, Float>> =
    (-2..2)
        .flatMap { ix -> (-3..3).map { iy -> ix * 32f to iy * 16f } }
        .sortedWith(compareBy<Pair<Float, Float>> { it.first * it.first + it.second * it.second }.thenBy { it.second }.thenBy { it.first })
