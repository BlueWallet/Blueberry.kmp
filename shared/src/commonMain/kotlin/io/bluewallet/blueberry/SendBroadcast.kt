package io.bluewallet.blueberry

enum class BroadcastTone { Working, Sent, Failed }

data class BroadcastStatusUi(
    val title: String,
    val line: String,
    val caption: String?,
    val tone: BroadcastTone,
    /** Sent, but no node confirmed it, so the same transaction can be given to nodes again. */
    val resend: Boolean = false,
)

private const val DONE_PERCENT = 100

fun broadcastJobInFlight(phase: String?): Boolean = phase != null && phase != "success" && phase != "error"

fun broadcastTorCaption(stage: String?): String? = stage?.let { "Tor: $it" }

/** Removes a failed broadcast from the list. No node holds it, so nothing else needs undoing. */
fun forgetBroadcast(
    runtime: PeersRuntime,
    id: String,
) {
    runtime.dropArmedBroadcast(id)
    runtime.broadcastStore.dismiss(id)
}

/** Plain-language status for the broadcast snapshot. Peers and attempts stay out of the main line. */
fun broadcastStatus(snap: BroadcastSnapshot): BroadcastStatusUi =
    when {
        snap.phase == "success" && !snap.acknowledged ->
            BroadcastStatusUi(
                "Sent",
                "Given to Bitcoin nodes",
                "No node confirmed it took it, but one may still pass it on, so its coins stay locked until it confirms. " +
                    "Send again gives the same transaction to other nodes.",
                BroadcastTone.Sent,
                resend = true,
            )
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
                "No node kept this transaction. Retry sends the same one again.",
                BroadcastTone.Failed,
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
): BroadcastStatusUi =
    if (snap.txHex == txHex) {
        broadcastStatus(snap)
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
                    else -> "Broadcasting"
                },
        )
    }
