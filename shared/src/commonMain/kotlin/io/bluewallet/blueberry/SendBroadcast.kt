package io.bluewallet.blueberry

enum class BroadcastTone { Working, Sent, Failed }

data class BroadcastStatusUi(
    val title: String,
    val line: String,
    val caption: String?,
    val tone: BroadcastTone,
)

private const val DONE_PERCENT = 100

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
        cancelling -> BroadcastStatusUi("Sending", "Stopping…", "Tap again to close without waiting.", BroadcastTone.Working)
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

/** True once the store holds this transaction's job, running or finished. */
fun broadcastOwns(
    snap: BroadcastSnapshot,
    txHex: String?,
): Boolean = txHex != null && snap.txHex == txHex && snap.phase != null
