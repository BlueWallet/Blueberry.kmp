package io.bluewallet.blueberry

import io.bluewallet.blueberry.wallet.ACCOUNT_UR_TYPES
import io.bluewallet.blueberry.wallet.UR_CORRUPT
import io.bluewallet.blueberry.wallet.UR_NOT_ACCOUNT
import io.bluewallet.blueberry.wallet.UR_NOT_UR
import io.bluewallet.blueberry.wallet.UrAccount
import io.bluewallet.blueberry.wallet.UrDecoder
import io.bluewallet.blueberry.wallet.UrReceive
import io.bluewallet.blueberry.wallet.parseUrAccounts

internal const val NO_ACCOUNTS = "No single-sig Bitcoin accounts in this QR."

internal sealed class AccountsScanState {
    /** [percent] is null until an animated QR part is read; [note] is why the last text didn't count. */
    data class Scanning(
        val percent: Int?,
        val note: String?,
    ) : AccountsScanState()

    /** A complete account export; never empty. */
    data class Accounts(
        val accounts: List<UrAccount>,
    ) : AccountsScanState()

    /** A QR that isn't a UR, for the import text field. */
    data class Text(
        val text: String,
    ) : AccountsScanState() {
        override fun toString(): String = "Text([redacted])"
    }
}

/**
 * Camera text in, accounts out: single or animated `ur:crypto-account`, `ur:crypto-multi-accounts` or
 * `ur:crypto-hdkey` frames. Any other QR read before a UR part is handed over as text. Not thread-safe;
 * feed it from one thread.
 */
internal class AccountsScan {
    private val decoder = UrDecoder(ACCOUNT_UR_TYPES, UR_NOT_ACCOUNT)
    private var lastText: String? = null

    // Why the last complete scan didn't count; stays up while frames keep coming.
    private var failure: String? = null

    var state: AccountsScanState = AccountsScanState.Scanning(null, null)
        private set

    fun accept(text: String): AccountsScanState {
        if (state !is AccountsScanState.Scanning || text == lastText) return state
        lastText = text
        val trimmed = text.trim()
        state =
            when {
                trimmed.startsWith("ur:", ignoreCase = true) -> fromUr(decoder.receive(trimmed))
                // A failure reset the decoder, but the account scan is still on: stray text mustn't end it.
                decoder.percent == null && failure == null -> AccountsScanState.Text(text)
                else -> scanning(UR_NOT_UR)
            }
        return state
    }

    private fun fromUr(step: UrReceive): AccountsScanState =
        when (step) {
            is UrReceive.Progress -> scanning(null)
            is UrReceive.Ignored -> scanning(step.reason)
            is UrReceive.Failed -> fail(step.reason)
            is UrReceive.Complete -> {
                val accounts = runCatching { parseUrAccounts(checkNotNull(decoder.type), step.message) }.getOrNull()
                when {
                    accounts == null -> fail(UR_CORRUPT)
                    accounts.isEmpty() -> fail(NO_ACCOUNTS)
                    else -> AccountsScanState.Accounts(accounts)
                }
            }
        }

    /** Starts the decoder over and keeps [reason] up; the last text is kept, so the same QR isn't parsed again. */
    private fun fail(reason: String): AccountsScanState {
        decoder.reset()
        failure = reason
        return scanning(null)
    }

    private fun scanning(note: String?): AccountsScanState = AccountsScanState.Scanning(decoder.percent, failure ?: note)
}
