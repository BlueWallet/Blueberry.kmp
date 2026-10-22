package io.bluewallet.blueberry

enum class BroadcastTone { Working, Sent, Failed }

data class BroadcastStatusUi(
    val title: String,
    val line: String,
    val caption: String?,
    val tone: BroadcastTone,
)

private const val DONE_PERCENT = 100

fun broadcastJobInFlight(phase: String?): Boolean = phase != null && phase != "success" && phase != "error"

/** The user stopped this broadcast. Other failures still belong on screen so they can be retried. */
fun broadcastCancelled(error: String?): Boolean = error?.trim()?.equals("cancelled", ignoreCase = true) == true

fun broadcastTorCaption(stage: String?): String? = stage?.let { "Tor: $it" }

/** Removes a cancelled broadcast from the list and releases the coins it was holding. */
fun forgetBroadcast(
    runtime: PeersRuntime,
    id: String,
) {
    runtime.dropArmedBroadcast(id)
    runtime.broadcastStore.dismiss(id)
}

/** Plain-language status for the broadcast snapshot. Peers and attempts stay out of the main line. */
fun broadcastStatus(
    snap: BroadcastSnapshot,
    cancelling: Boolean = false,
): BroadcastStatusUi =
    when {
        snap.phase == "success" ->
            BroadcastStatusUi(
                "Sent",
                "Sent privately",
                "Accepted by Bitcoin nodes over Tor. It shows in Activity once it confirms.",
                BroadcastTone.Sent,
            )
        snap.phase == "error" ->
            BroadcastStatusUi(
                "Not sent yet",
                snap.error?.takeIf { it.isNotBlank() } ?: "No node accepted it.",
                "Retrying sends the same transaction, so it can't pay twice.",
                BroadcastTone.Failed,
            )
        snap.stopRequested || cancelling ->
            BroadcastStatusUi(
                "Stopping",
                "This send will not go out",
                "The circuit it already opened is closing.",
                BroadcastTone.Working,
            )
        else -> workingStatus(snap)
    }

private fun workingStatus(snap: BroadcastSnapshot): BroadcastStatusUi {
    val percent = snap.percent
    val attempt = snap.attempt?.let { n -> snap.maxAttempts?.let { "Attempt $n of $it" } ?: "Attempt $n" }
    return when {
        snap.phase == "failed-attempt" -> BroadcastStatusUi("Sending", "Trying another node", "A node didn't answer.", BroadcastTone.Working)
        snap.phase == "attempt" && percent != null && percent < DONE_PERCENT ->
            BroadcastStatusUi("Sending", "Building a private circuit", "Tor $percent%", BroadcastTone.Working)
        snap.phase == "attempt" -> BroadcastStatusUi("Sending", "Handing it to Bitcoin nodes", attempt, BroadcastTone.Working)
        snap.phase == "waiting-peers" -> BroadcastStatusUi("Sending", "Finding Bitcoin nodes", null, BroadcastTone.Working)
        else ->
            BroadcastStatusUi(
                "Sending",
                "Starting Tor",
                "Your transaction never touches a server we run.",
                BroadcastTone.Working,
            )
    }
}

/**
 * Status for one payment. A store snapshot for a different transaction (another send still
 * in flight, or a finished one not yet reset) must not read as Sent on this receipt.
 */
fun ownedBroadcastStatus(
    snap: BroadcastSnapshot,
    txHex: String,
    cancelling: Boolean = false,
): BroadcastStatusUi =
    if (snap.txHex == txHex) {
        broadcastStatus(snap, cancelling)
    } else {
        BroadcastStatusUi(
            "Sending",
            "Starting Tor",
            "Your transaction never touches a server we run.",
            BroadcastTone.Working,
        )
    }

/** True once the store holds this transaction's job, running or finished. */
fun broadcastOwns(
    snap: BroadcastSnapshot,
    txHex: String?,
): Boolean = txHex != null && snap.txHex == txHex && snap.phase != null

/** A home-list row for a broadcast that is not already a wallet transaction. */
data class BroadcastListRow(
    val id: String,
    val txid: String,
    val title: String,
    val paidSats: Long,
    val status: String,
)

fun broadcastListRows(
    sessions: List<BroadcastSession>,
    walletTxids: Set<String>,
): List<BroadcastListRow> =
    sessions.asReversed().mapNotNull { session ->
        val snap = session.snapshot
        val receipt = session.receipt ?: return@mapNotNull null
        val id = snap.id ?: return@mapNotNull null
        if (snap.phase == null || receipt.txid in walletTxids) return@mapNotNull null
        BroadcastListRow(
            id = id,
            txid = receipt.txid,
            title = receipt.label.ifBlank { receipt.destination },
            paidSats = receipt.paidSats,
            status =
                when {
                    snap.phase == "error" -> "Not sent"
                    snap.phase == "success" -> "Sent"
                    snap.stopRequested -> "Stopping"
                    else -> "Broadcasting"
                },
        )
    }
