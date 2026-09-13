package io.bluewallet.blueberry.onboarding

import io.bluewallet.blueberry.boot.DEFAULT_CHECKPOINT_YEAR
import io.bluewallet.blueberry.wallet.parseWalletSecret
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnboardingFlowTest {
    private val abandon =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    @Test
    fun choose_create_stores_twelve_words() {
        val start = initialOnboardingState(startAtYearStep = false)
        assertEquals(OnboardingStep.Choose, start.step)
        val result = reduceOnboarding(start, OnboardingEvent.ChooseCreate)
        assertEquals(OnboardingStep.Create, result.state.step)
        val words = result.state.mnemonic?.split(" ")
        assertNotNull(words)
        assertEquals(12, words.size)
        parseWalletSecret(result.state.mnemonic!!)
        assertNull(result.effect)
    }

    @Test
    fun choose_import_and_back() {
        val imported =
            reduceOnboarding(
                initialOnboardingState(false),
                OnboardingEvent.ChooseImport,
            )
        assertEquals(OnboardingStep.Import, imported.state.step)
        assertEquals("", imported.state.importValue)

        val created =
            reduceOnboarding(
                initialOnboardingState(false),
                OnboardingEvent.ChooseCreate,
            )
        val backFromCreate = reduceOnboarding(created.state, OnboardingEvent.Back)
        assertEquals(OnboardingStep.Choose, backFromCreate.state.step)
        assertNull(backFromCreate.state.mnemonic)

        val typed =
            reduceOnboarding(
                imported.state,
                OnboardingEvent.ImportChanged("abc"),
            )
        val backFromImport = reduceOnboarding(typed.state, OnboardingEvent.Back)
        assertEquals(OnboardingStep.Choose, backFromImport.state.step)
        assertEquals("", backFromImport.state.importValue)
    }

    @Test
    fun submit_import_good_then_year_after_persist_ok() {
        var state =
            reduceOnboarding(
                initialOnboardingState(false),
                OnboardingEvent.ChooseImport,
            ).state
        state = reduceOnboarding(state, OnboardingEvent.ImportChanged(abandon)).state
        val submitted = reduceOnboarding(state, OnboardingEvent.SubmitImport)
        assertEquals(OnboardingStep.Import, submitted.state.step)
        assertTrue(submitted.state.busy)
        assertEquals(OnboardingEffect.PersistImportedSecret(abandon), submitted.effect)

        val year = reduceOnboarding(submitted.state, OnboardingEvent.PersistImportOk)
        assertEquals(OnboardingStep.Year, year.state.step)
        assertFalse(year.state.busy)
        assertNull(year.state.error)
        assertEquals(DEFAULT_CHECKPOINT_YEAR, year.state.selectedYear)
        assertNull(year.effect)
    }

    @Test
    fun submit_import_bad_stays_with_parse_error() {
        var state =
            reduceOnboarding(
                initialOnboardingState(false),
                OnboardingEvent.ChooseImport,
            ).state
        state =
            reduceOnboarding(
                state,
                OnboardingEvent.ImportChanged(
                    "zzzz zzzz zzzz zzzz zzzz zzzz zzzz zzzz zzzz zzzz zzzz zzzz",
                ),
            ).state
        val submitted = reduceOnboarding(state, OnboardingEvent.SubmitImport)
        assertEquals(OnboardingStep.Import, submitted.state.step)
        assertEquals("invalid BIP39 mnemonic", submitted.state.error)
        assertFalse(submitted.state.busy)
        assertNull(submitted.effect)
    }

    @Test
    fun confirm_create_emits_persist_created_not_year() {
        val created =
            reduceOnboarding(
                initialOnboardingState(false),
                OnboardingEvent.ChooseCreate,
            )
        val confirmed = reduceOnboarding(created.state, OnboardingEvent.ConfirmCreate)
        assertEquals(OnboardingStep.Create, confirmed.state.step)
        assertTrue(confirmed.state.busy)
        val effect = confirmed.effect as OnboardingEffect.PersistCreatedWallet
        assertEquals(created.state.mnemonic, effect.mnemonic)
    }

    @Test
    fun confirm_year_emits_selected_year() {
        var state = initialOnboardingState(startAtYearStep = true)
        assertEquals(OnboardingStep.Year, state.step)
        assertEquals(DEFAULT_CHECKPOINT_YEAR, state.selectedYear)
        state = reduceOnboarding(state, OnboardingEvent.SelectYear(2015)).state
        val confirmed = reduceOnboarding(state, OnboardingEvent.ConfirmYear)
        assertTrue(confirmed.state.busy)
        assertEquals(OnboardingEffect.PersistYear(2015), confirmed.effect)
    }

    @Test
    fun back_on_year_does_nothing() {
        val start = initialOnboardingState(startAtYearStep = true)
        val back = reduceOnboarding(start, OnboardingEvent.Back)
        assertEquals(start, back.state)
        assertNull(back.effect)
    }

    @Test
    fun confirm_while_busy_is_ignored() {
        var state =
            reduceOnboarding(
                initialOnboardingState(false),
                OnboardingEvent.ChooseImport,
            ).state
        state = reduceOnboarding(state, OnboardingEvent.ImportChanged(abandon)).state
        val busy = reduceOnboarding(state, OnboardingEvent.SubmitImport)
        assertTrue(busy.state.busy)
        val ignored = reduceOnboarding(busy.state, OnboardingEvent.ConfirmYear)
        assertEquals(busy.state, ignored.state)
        assertNull(ignored.effect)
        val stillBusy = reduceOnboarding(busy.state, OnboardingEvent.SubmitImport)
        assertEquals(busy.state, stillBusy.state)
        assertNull(stillBusy.effect)
    }

    @Test
    fun secret_values_are_redacted_in_to_string() {
        val values =
            listOf(
                OnboardingState(
                    step = OnboardingStep.Create,
                    importValue = abandon,
                    mnemonic = abandon,
                ),
                OnboardingEvent.ImportChanged(abandon),
                OnboardingEffect.PersistImportedSecret(abandon),
                OnboardingEffect.PersistCreatedWallet(abandon),
            )

        values.forEach { value ->
            assertFalse(value.toString().contains(abandon))
            assertFalse(value.toString().contains("abandon"))
            assertTrue(value.toString().contains("[redacted]"))
        }
    }
}
