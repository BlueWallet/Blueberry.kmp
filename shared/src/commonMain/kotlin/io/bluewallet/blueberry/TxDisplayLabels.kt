package io.bluewallet.blueberry

private const val LABEL_SUGGESTION_LIMIT = 4

/** Most-used labels first, then alphabetical, at most [limit]. Blank labels are dropped. */
fun labelSuggestions(
    labels: List<String>,
    limit: Int = LABEL_SUGGESTION_LIMIT,
): List<String> {
    val counts = linkedMapOf<String, Int>()
    labels.forEach { raw ->
        val label = raw.trim()
        if (label.isNotEmpty()) counts[label] = (counts[label] ?: 0) + 1
    }
    return counts.entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .take(limit)
        .map { it.key }
}

/**
 * Outpoints whose name should take [nextLabel]: empty, or still equal to the previous transaction label.
 * [names] maps outpoint to the current coin name.
 */
fun coinNamesToUpdate(
    names: Map<String, String?>,
    previousLabel: String,
): List<String> {
    val previous = previousLabel.trim()
    return names
        .filter { (_, name) ->
            val current = name?.trim().orEmpty()
            current.isEmpty() || current == previous
        }.keys
        .sorted()
}
