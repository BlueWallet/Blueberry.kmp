package io.bluewallet.blueberry

import io.bluewallet.blueberry.parse.CHANGE_FROM_PREFIX

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
 * Outpoints whose name should take the next transaction label: empty, still equal to the previous
 * note, or still equal to the automatic `change from: {note}` name. When [hasChangeChain] is true,
 * that automatic name is only rewritten on [changeOutpoints].
 */
fun coinNamesToUpdate(
    names: Map<String, String?>,
    previousLabel: String,
    changeOutpoints: Set<String> = emptySet(),
    hasChangeChain: Boolean = false,
): List<String> {
    val previous = previousLabel.trim()
    val derived = if (previous.isEmpty()) null else "$CHANGE_FROM_PREFIX$previous"
    return names
        .filter { (outpoint, name) ->
            val current = name?.trim().orEmpty()
            current.isEmpty() ||
                current == previous ||
                (current == derived && (!hasChangeChain || outpoint in changeOutpoints))
        }.keys
        .sorted()
}
