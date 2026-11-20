package io.bluewallet.blueberry

/**
 * In-progress address-label drafts, keyed by address so a flush cannot write one
 * output's text onto another. [flush] persists every pending draft.
 */
internal class AddressLabelDrafts(
    private val save: (String, String) -> Unit,
) {
    private val unsaved = mutableMapOf<String, String>()

    fun actions(address: String): CoinLabelActions =
        CoinLabelActions(
            onSave = { text ->
                unsaved.remove(address)
                save(address, text)
            },
            onDraft = { draft ->
                if (draft != null) unsaved[address] = draft else unsaved.remove(address)
            },
        )

    fun flush() {
        val pending = unsaved.toList()
        unsaved.clear()
        pending.forEach { (address, text) -> save(address, text) }
    }
}
