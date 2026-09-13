package io.bluewallet.blueberry.boot

import io.bluewallet.blueberry.wallet.WalletSecretInspection

sealed class OnboardingGate {
    data class ExitInvalid(
        val detail: String,
    ) : OnboardingGate()

    data class Onboard(
        val startAtYearStep: Boolean,
    ) : OnboardingGate()

    data object Start : OnboardingGate()
}

fun resolveOnboardingGate(
    wallet: WalletSecretInspection,
    year: SyncFromYearInspection,
): OnboardingGate {
    if (wallet is WalletSecretInspection.Invalid) {
        return OnboardingGate.ExitInvalid(wallet.detail)
    }
    if (wallet is WalletSecretInspection.Missing) {
        return OnboardingGate.Onboard(startAtYearStep = false)
    }
    if (year is SyncFromYearInspection.Missing) {
        return OnboardingGate.Onboard(startAtYearStep = true)
    }
    return OnboardingGate.Start
}
