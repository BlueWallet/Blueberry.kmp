package io.bluewallet.blueberry.onboarding

import io.bluewallet.blueberry.boot.DEFAULT_CHECKPOINT_YEAR
import io.bluewallet.blueberry.boot.listCheckpointYears
import io.bluewallet.blueberry.wallet.AddressScriptType
import io.bluewallet.blueberry.wallet.UrAccount
import io.bluewallet.blueberry.wallet.generateMnemonic12
import io.bluewallet.blueberry.wallet.parseWalletSecret

enum class OnboardingStep { Choose, Import, Create, Year }

fun onboardingNavigatesForward(
    from: OnboardingStep,
    to: OnboardingStep,
): Boolean = to.ordinal > from.ordinal

data class OnboardingState(
    val step: OnboardingStep,
    val importValue: String = "",
    val error: String? = null,
    val mnemonic: String? = null,
    val selectedYear: Int = DEFAULT_CHECKPOINT_YEAR,
    val busy: Boolean = false,
    /** Accounts of a scanned export to pick from; empty when there is nothing to pick. */
    val accounts: List<UrAccount> = emptyList(),
) {
    override fun toString(): String = "OnboardingState(step=$step, importValue=[redacted], error=$error, mnemonic=[redacted], selectedYear=$selectedYear, busy=$busy, accounts=${accounts.size})"
}

/** The picker's starting row: the first Native SegWit account, else the first. */
fun preselectedAccount(accounts: List<UrAccount>): UrAccount? = accounts.firstOrNull { it.scriptType == AddressScriptType.P2WPKH } ?: accounts.firstOrNull()

sealed class OnboardingEvent {
    data object ChooseCreate : OnboardingEvent()

    data object ChooseImport : OnboardingEvent()

    data object Back : OnboardingEvent()

    data class ImportChanged(
        val value: String,
    ) : OnboardingEvent() {
        override fun toString(): String = "ImportChanged(value=[redacted])"
    }

    data object SubmitImport : OnboardingEvent()

    /** A scanned account export with at least one account. */
    data class ScannedAccounts(
        val accounts: List<UrAccount>,
    ) : OnboardingEvent() {
        override fun toString(): String = "ScannedAccounts(${accounts.size})"
    }

    data class PickAccount(
        val account: UrAccount,
    ) : OnboardingEvent()

    data object ConfirmCreate : OnboardingEvent()

    data class SelectYear(
        val year: Int,
    ) : OnboardingEvent()

    data object ConfirmYear : OnboardingEvent()

    data class PersistFailed(
        val message: String,
    ) : OnboardingEvent()

    data object PersistImportOk : OnboardingEvent()

    /** Secret delete finished; leave Year for Import. */
    data object ForgetSecretOk : OnboardingEvent()
}

sealed class OnboardingEffect {
    data class PersistImportedSecret(
        val raw: String,
    ) : OnboardingEffect() {
        override fun toString(): String = "PersistImportedSecret(raw=[redacted])"
    }

    data class PersistCreatedWallet(
        val mnemonic: String,
    ) : OnboardingEffect() {
        override fun toString(): String = "PersistCreatedWallet(mnemonic=[redacted])"
    }

    data class PersistYear(
        val year: Int,
    ) : OnboardingEffect()

    data object ForgetImportedSecret : OnboardingEffect()
}

data class OnboardingReduction(
    val state: OnboardingState,
    val effect: OnboardingEffect? = null,
)

fun initialOnboardingState(startAtYearStep: Boolean): OnboardingState =
    if (startAtYearStep) {
        OnboardingState(step = OnboardingStep.Year)
    } else {
        OnboardingState(step = OnboardingStep.Choose)
    }

private fun onboardingAcceptsWhileBusy(event: OnboardingEvent): Boolean =
    event is OnboardingEvent.PersistFailed ||
        event is OnboardingEvent.PersistImportOk ||
        event is OnboardingEvent.ForgetSecretOk

fun reduceOnboarding(
    state: OnboardingState,
    event: OnboardingEvent,
): OnboardingReduction {
    if (state.busy && !onboardingAcceptsWhileBusy(event)) {
        return OnboardingReduction(state)
    }
    return when (event) {
        OnboardingEvent.ChooseCreate -> {
            if (state.step != OnboardingStep.Choose) return OnboardingReduction(state)
            OnboardingReduction(
                state.copy(
                    step = OnboardingStep.Create,
                    mnemonic = generateMnemonic12(),
                    error = null,
                    importValue = "",
                ),
            )
        }
        OnboardingEvent.ChooseImport -> {
            if (state.step != OnboardingStep.Choose) return OnboardingReduction(state)
            OnboardingReduction(
                state.copy(
                    step = OnboardingStep.Import,
                    importValue = "",
                    error = null,
                    mnemonic = null,
                ),
            )
        }
        OnboardingEvent.Back -> {
            when (state.step) {
                OnboardingStep.Create, OnboardingStep.Import ->
                    if (state.accounts.isNotEmpty()) {
                        // Back from the account picker returns to the import field.
                        OnboardingReduction(state.copy(accounts = emptyList()))
                    } else {
                        OnboardingReduction(OnboardingState(step = OnboardingStep.Choose))
                    }
                OnboardingStep.Year ->
                    if (state.importValue.isEmpty()) {
                        OnboardingReduction(state)
                    } else {
                        // Stay on Year until the secret is gone. A failed delete must not
                        // leave Import, or the next cold start hides Back.
                        OnboardingReduction(
                            state.copy(busy = true, error = null),
                            OnboardingEffect.ForgetImportedSecret,
                        )
                    }
                OnboardingStep.Choose -> OnboardingReduction(state)
            }
        }
        is OnboardingEvent.ImportChanged -> {
            if (state.step != OnboardingStep.Import || state.accounts.isNotEmpty()) return OnboardingReduction(state)
            OnboardingReduction(state.copy(importValue = event.value, error = null))
        }
        is OnboardingEvent.ScannedAccounts -> {
            if (state.step != OnboardingStep.Import || event.accounts.isEmpty()) return OnboardingReduction(state)
            event.accounts.singleOrNull()?.let { return importAccount(state, it) }
            OnboardingReduction(state.copy(accounts = event.accounts, error = null))
        }
        is OnboardingEvent.PickAccount -> {
            if (state.step != OnboardingStep.Import || event.account !in state.accounts) return OnboardingReduction(state)
            importAccount(state, event.account)
        }
        OnboardingEvent.SubmitImport -> {
            if (state.step != OnboardingStep.Import) return OnboardingReduction(state)
            try {
                parseWalletSecret(state.importValue)
            } catch (err: Exception) {
                return OnboardingReduction(
                    state.copy(error = err.message ?: err.toString(), busy = false),
                )
            }
            OnboardingReduction(
                state.copy(busy = true, error = null),
                OnboardingEffect.PersistImportedSecret(state.importValue),
            )
        }
        OnboardingEvent.ConfirmCreate -> {
            val mnemonic = state.mnemonic
            if (state.step != OnboardingStep.Create || mnemonic == null) {
                return OnboardingReduction(state)
            }
            OnboardingReduction(
                state.copy(busy = true, error = null),
                OnboardingEffect.PersistCreatedWallet(mnemonic),
            )
        }
        is OnboardingEvent.SelectYear -> {
            if (state.step != OnboardingStep.Year) return OnboardingReduction(state)
            if (event.year !in listCheckpointYears()) return OnboardingReduction(state)
            OnboardingReduction(state.copy(selectedYear = event.year))
        }
        OnboardingEvent.ConfirmYear -> {
            if (state.step != OnboardingStep.Year) return OnboardingReduction(state)
            OnboardingReduction(
                state.copy(busy = true, error = null),
                OnboardingEffect.PersistYear(state.selectedYear),
            )
        }
        is OnboardingEvent.PersistFailed -> {
            OnboardingReduction(state.copy(busy = false, error = event.message))
        }
        OnboardingEvent.PersistImportOk -> {
            if (state.step != OnboardingStep.Import) return OnboardingReduction(state)
            OnboardingReduction(
                state.copy(
                    step = OnboardingStep.Year,
                    busy = false,
                    error = null,
                    selectedYear = DEFAULT_CHECKPOINT_YEAR,
                ),
            )
        }
        OnboardingEvent.ForgetSecretOk -> {
            if (state.step != OnboardingStep.Year) return OnboardingReduction(state)
            OnboardingReduction(
                state.copy(step = OnboardingStep.Import, busy = false, error = null),
            )
        }
    }
}

/** Imports [account] as its descriptor, as Continue does for a typed secret. */
private fun importAccount(
    state: OnboardingState,
    account: UrAccount,
): OnboardingReduction =
    OnboardingReduction(
        state.copy(importValue = account.descriptor, accounts = emptyList(), busy = true, error = null),
        OnboardingEffect.PersistImportedSecret(account.descriptor),
    )
