package io.bluewallet.blueberry

import io.bluewallet.blueberry.wallet.PSBT_NOT_A_PSBT
import io.bluewallet.blueberry.wallet.PSBT_UR_TYPES
import io.bluewallet.blueberry.wallet.UR_CORRUPT
import io.bluewallet.blueberry.wallet.UrDecoder
import io.bluewallet.blueberry.wallet.UrReceive
import io.bluewallet.blueberry.wallet.psbtBytesFromText
import io.bluewallet.blueberry.wallet.psbtFromUrMessage

internal sealed class SignedPsbtScanState {
    /** [percent] is null until an animated QR part is read; [note] is why the last text didn't count. */
    data class Scanning(
        val percent: Int?,
        val note: String?,
    ) : SignedPsbtScanState()

    class Done(
        val psbt: ByteArray,
    ) : SignedPsbtScanState()
}

/**
 * Camera text in, PSBT bytes out: animated or single `ur:crypto-psbt` / `ur:psbt` frames, or one QR
 * holding the PSBT as base64 or hex. Not thread-safe; feed it from one thread.
 */
internal class SignedPsbtScan {
    private val decoder = UrDecoder(PSBT_UR_TYPES)
    private var lastText: String? = null

    var state: SignedPsbtScanState = SignedPsbtScanState.Scanning(null, null)
        private set

    fun accept(text: String): SignedPsbtScanState {
        if (state is SignedPsbtScanState.Done || text == lastText) return state
        lastText = text
        val trimmed = text.trim()
        state =
            if (trimmed.startsWith("ur:", ignoreCase = true)) {
                fromUr(decoder.receive(trimmed))
            } else {
                psbtBytesFromText(trimmed)?.let { SignedPsbtScanState.Done(it) } ?: scanning(PSBT_NOT_A_PSBT)
            }
        return state
    }

    fun reset(note: String? = null) {
        decoder.reset()
        lastText = null
        state = SignedPsbtScanState.Scanning(null, note)
    }

    private fun fromUr(step: UrReceive): SignedPsbtScanState =
        when (step) {
            is UrReceive.Progress -> scanning(null)
            is UrReceive.Ignored -> scanning(step.reason)
            is UrReceive.Failed -> {
                reset(step.reason)
                state
            }
            is UrReceive.Complete -> {
                val psbt = runCatching { psbtFromUrMessage(step.message) }.getOrNull()
                if (psbt == null) reset(UR_CORRUPT)
                psbt?.let { SignedPsbtScanState.Done(it) } ?: state
            }
        }

    private fun scanning(note: String?): SignedPsbtScanState = SignedPsbtScanState.Scanning(decoder.percent, note)
}
