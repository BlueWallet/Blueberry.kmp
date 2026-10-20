package io.bluewallet.blueberry

import io.bluewallet.blueberry.parse.ReceiveChain
import io.bluewallet.blueberry.storage.createSqliteDatabase
import io.bluewallet.blueberry.wallet.WatchAddress
import io.bluewallet.blueberry.wallet.saveWalletSecret
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReceiveScreenTest {
    @Test
    fun receive_type_picker_is_only_shown_for_mnemonic_secrets() {
        val db = createSqliteDatabase(":memory:")
        saveWalletSecret(
            db,
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
        )
        assertTrue(showsReceiveTypePicker(db))

        saveWalletSecret(
            db,
            "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs",
        )
        assertFalse(showsReceiveTypePicker(db))
        db.close()
    }

    @Test
    fun label_sheet_shows_for_unlabelled_or_edited_address_only() {
        assertTrue(showsAddressLabelSheet(address = "bc1qa", label = null, editing = false))
        assertTrue(showsAddressLabelSheet(address = "bc1qa", label = "", editing = false))
        assertTrue(showsAddressLabelSheet(address = "bc1qa", label = "  \t ", editing = false))
        assertFalse(showsAddressLabelSheet(address = "bc1qa", label = "rent", editing = false))
        assertTrue(showsAddressLabelSheet(address = "bc1qa", label = "rent", editing = true))
        assertFalse(showsAddressLabelSheet(address = null, label = null, editing = false))
    }

    @Test
    fun label_save_needs_non_blank_draft() {
        assertFalse(canSaveAddressLabel(""))
        assertFalse(canSaveAddressLabel("  \t "))
        assertTrue(canSaveAddressLabel(" rent "))
    }

    @Test
    fun step_stays_between_zero_and_last_index() {
        assertFalse(canStepReceive(index = 0, delta = -1, lastIndex = 19))
        assertTrue(canStepReceive(index = 0, delta = 1, lastIndex = 19))
        assertTrue(canStepReceive(index = 5, delta = -1, lastIndex = 19))
        assertTrue(canStepReceive(index = 18, delta = 1, lastIndex = 19))
        assertFalse(canStepReceive(index = 19, delta = 1, lastIndex = 19))
    }

    @Test
    fun path_caption_is_hidden_until_the_address_is_labelled() {
        assertEquals("m/84'/0'/0'/0/5", receivePathCaption("m/84'/0'/0'/0/5", hidden = false))
        assertEquals(PLACEHOLDER_PATH, receivePathCaption("m/84'/0'/0'/0/5", hidden = true))
        assertFalse(PLACEHOLDER_PATH.any { it.isLetterOrDigit() })
    }

    @Test
    fun steps_queued_before_the_next_frame_never_pass_the_bound() {
        val chain = ReceiveChain((0 until 20).map { WatchAddress("m/84'/0'/0'/0/$it", it, false, "a$it", ByteArray(0)) }, emptySet(), 18)
        var index = 18
        val nav = receiveNav(chain, index) { index = it }!!
        // Two taps/key repeats against the same frame's nav: the second must not step past index 19.
        nav.step(1)
        nav.step(1)
        assertEquals(19, index)
        assertFalse(receiveNav(chain, 19) {}!!.step(1))
    }
}
