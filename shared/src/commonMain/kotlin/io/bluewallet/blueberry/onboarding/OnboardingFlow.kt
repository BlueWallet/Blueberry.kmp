package io.bluewallet.blueberry.onboarding

import io.bluewallet.blueberry.boot.DEFAULT_CHECKPOINT_YEAR
import io.bluewallet.blueberry.boot.listCheckpointYears
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
) {
    override fun toString(): String = "OnboardingState(step=$step, importValue=[redacted], error=$error, mnemonic=[redacted], selectedYear=$selectedYear, busy=$busy)"
}

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

    data object ConfirmCreate : OnboardingEvent()

    data class SelectYear(
        val year: Int,
    ) : OnboardingEvent()

    data object ConfirmYear : OnboardingEvent()

    data class PersistFailed(
        val message: String,
    ) : OnboardingEvent()

    data object PersistImportOk : OnboardingEvent()
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

fun reduceOnboarding(
    state: OnboardingState,
    event: OnboardingEvent,
): OnboardingReduction {
    if (state.busy && event !is OnboardingEvent.PersistFailed && event !is OnboardingEvent.PersistImportOk) {
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
            if (state.step != OnboardingStep.Create && state.step != OnboardingStep.Import) {
                return OnboardingReduction(state)
            }
            OnboardingReduction(
                OnboardingState(step = OnboardingStep.Choose),
            )
        }
        is OnboardingEvent.ImportChanged -> {
            if (state.step != OnboardingStep.Import) return OnboardingReduction(state)
            OnboardingReduction(state.copy(importValue = event.value, error = null))
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
    }
}
