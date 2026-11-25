package io.bluewallet.blueberry.onboarding

import fr.acinq.bitcoin.KeyPath
import io.bluewallet.blueberry.boot.DEFAULT_CHECKPOINT_YEAR
import io.bluewallet.blueberry.wallet.AddressScriptType
import io.bluewallet.blueberry.wallet.UrAccount
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

    // Keystone's abandon account keys (spec appendix A), as the scanner hands them over.
    private val scannedTaproot =
        UrAccount(
            AddressScriptType.P2TR,
            0x73c5da0aL,
            KeyPath("m/86'/0'/0'"),
            "xpub6BgBgsespWvERF3LHQu6CnqdvfEvtMcQjYrcRzx53QJjSxarj2afYWcLteoGVky7D3UKDP9QyrLprQ3VCECoY49yfdDEHGCtMMj92pReUsQ",
        )
    private val scannedNativeSegwit =
        UrAccount(
            AddressScriptType.P2WPKH,
            0x73c5da0aL,
            KeyPath("m/84'/0'/0'"),
            "xpub6CatWdiZiodmUeTDp8LT5or8nmbKNcuyvz7WyksVFkKB4RHwCD3XyuvPEbvqAQY3rAPshWcMLoP2fMFMKHPJ4ZeZXYVUhLv1VMrjPC7PW6V",
        )

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
    fun back_from_year_after_import_deletes_secret_before_leaving() {
        var state =
            reduceOnboarding(
                initialOnboardingState(false),
                OnboardingEvent.ChooseImport,
            ).state
        state = reduceOnboarding(state, OnboardingEvent.ImportChanged(abandon)).state
        val submitted = reduceOnboarding(state, OnboardingEvent.SubmitImport)
        val year = reduceOnboarding(submitted.state, OnboardingEvent.PersistImportOk).state
        val back = reduceOnboarding(year, OnboardingEvent.Back)
        assertEquals(OnboardingStep.Year, back.state.step)
        assertEquals(abandon, back.state.importValue)
        assertTrue(back.state.busy)
        assertEquals(OnboardingEffect.ForgetImportedSecret, back.effect)

        val forgotten = reduceOnboarding(back.state, OnboardingEvent.ForgetSecretOk)
        assertEquals(OnboardingStep.Import, forgotten.state.step)
        assertEquals(abandon, forgotten.state.importValue)
        assertFalse(forgotten.state.busy)
        assertNull(forgotten.effect)
    }

    @Test
    fun failed_secret_delete_stays_on_year() {
        var state =
            reduceOnboarding(
                initialOnboardingState(false),
                OnboardingEvent.ChooseImport,
            ).state
        state = reduceOnboarding(state, OnboardingEvent.ImportChanged(abandon)).state
        val submitted = reduceOnboarding(state, OnboardingEvent.SubmitImport)
        val year = reduceOnboarding(submitted.state, OnboardingEvent.PersistImportOk).state
        val back = reduceOnboarding(year, OnboardingEvent.Back).state
        val failed =
            reduceOnboarding(
                back,
                OnboardingEvent.PersistFailed("disk full"),
            )
        assertEquals(OnboardingStep.Year, failed.state.step)
        assertFalse(failed.state.busy)
        assertEquals("disk full", failed.state.error)
        assertNull(failed.effect)
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

    @Test
    fun one_scanned_account_is_imported_without_a_picker() {
        val import = reduceOnboarding(initialOnboardingState(false), OnboardingEvent.ChooseImport).state
        val result = reduceOnboarding(import, OnboardingEvent.ScannedAccounts(listOf(scannedTaproot)))
        assertEquals(OnboardingEffect.PersistImportedSecret(scannedTaproot.descriptor), result.effect)
        assertEquals(scannedTaproot.descriptor, result.state.importValue)
        assertTrue(result.state.accounts.isEmpty())
        assertTrue(result.state.busy)
        val year = reduceOnboarding(result.state, OnboardingEvent.PersistImportOk).state
        assertEquals(OnboardingStep.Year, year.step)
    }

    @Test
    fun several_scanned_accounts_show_the_picker_and_import_the_picked_one() {
        val import = reduceOnboarding(initialOnboardingState(false), OnboardingEvent.ChooseImport).state
        val accounts = listOf(scannedTaproot, scannedNativeSegwit)
        val picking = reduceOnboarding(import, OnboardingEvent.ScannedAccounts(accounts))
        assertNull(picking.effect)
        assertEquals(accounts, picking.state.accounts)
        assertEquals(scannedNativeSegwit, preselectedAccount(picking.state.accounts))
        assertEquals(picking.state, reduceOnboarding(picking.state, OnboardingEvent.ImportChanged("typed")).state)

        val picked = reduceOnboarding(picking.state, OnboardingEvent.PickAccount(scannedTaproot))
        assertEquals(OnboardingEffect.PersistImportedSecret(scannedTaproot.descriptor), picked.effect)
        assertTrue(picked.state.accounts.isEmpty())
        assertEquals(scannedTaproot.descriptor, picked.state.importValue)
    }

    @Test
    fun back_from_the_picker_returns_to_the_import_field() {
        val import = reduceOnboarding(initialOnboardingState(false), OnboardingEvent.ChooseImport).state
        val picking = reduceOnboarding(import, OnboardingEvent.ScannedAccounts(listOf(scannedTaproot, scannedNativeSegwit))).state
        val back = reduceOnboarding(picking, OnboardingEvent.Back)
        assertEquals(OnboardingStep.Import, back.state.step)
        assertTrue(back.state.accounts.isEmpty())
        assertNull(back.effect)
    }
}
