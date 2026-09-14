package io.bluewallet.blueberry.wallet

import io.bluewallet.blueberry.storage.createSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals

class ReceivePreferenceTest {
    @Test
    fun defaults_to_p2wpkh_and_persists() {
        val db = createSqliteDatabase(":memory:")
        assertEquals(AddressScriptType.P2WPKH, loadReceiveScriptType(db))
        saveReceiveScriptType(db, AddressScriptType.P2TR)
        assertEquals(AddressScriptType.P2TR, loadReceiveScriptType(db))
        db.keyValue.set(RECEIVE_SCRIPT_TYPE_KEY, "nope")
        assertEquals(AddressScriptType.P2WPKH, loadReceiveScriptType(db))
        db.close()
    }
}
