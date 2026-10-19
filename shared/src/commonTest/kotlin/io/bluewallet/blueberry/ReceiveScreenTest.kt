package io.bluewallet.blueberry

import io.bluewallet.blueberry.storage.createSqliteDatabase
import io.bluewallet.blueberry.wallet.saveWalletSecret
import kotlin.test.Test
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
}
