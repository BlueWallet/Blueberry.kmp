package io.bluewallet.blueberry.onboarding

import io.bluewallet.blueberry.boot.SYNC_FROM_YEAR_KEY
import io.bluewallet.blueberry.boot.SyncFromYearInspection
import io.bluewallet.blueberry.boot.inspectSyncFromYear
import io.bluewallet.blueberry.boot.latestCheckpointYear
import io.bluewallet.blueberry.boot.loadSyncFromYear
import io.bluewallet.blueberry.storage.createSqliteDatabase
import io.bluewallet.blueberry.wallet.WalletBirthdayInspection
import io.bluewallet.blueberry.wallet.WalletSecretInspection
import io.bluewallet.blueberry.wallet.inspectWalletBirthday
import io.bluewallet.blueberry.wallet.inspectWalletSecret
import io.bluewallet.blueberry.wallet.loadWalletSecret
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnboardingPersistTest {
    private val abandon =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    @Test
    fun import_writes_secret_only() {
        val db = createSqliteDatabase(":memory:")
        persistImportedSecret(db, "  $abandon  ")
        assertEquals(abandon, loadWalletSecret(db))
        assertEquals(SyncFromYearInspection.Missing, inspectSyncFromYear(db))
        assertEquals(WalletBirthdayInspection.None, inspectWalletBirthday(db))
        db.close()
    }

    @Test
    fun create_writes_secret_latest_year_and_birthday_pending() {
        val db = createSqliteDatabase(":memory:")
        persistCreatedWallet(db, abandon)
        assertEquals(abandon, loadWalletSecret(db))
        assertEquals(latestCheckpointYear(), loadSyncFromYear(db))
        assertEquals(WalletBirthdayInspection.Pending, inspectWalletBirthday(db))
        db.close()
    }

    @Test
    fun year_writes_sync_from_year() {
        val db = createSqliteDatabase(":memory:")
        persistSyncYear(db, 2015)
        assertEquals("2015", db.keyValue.get(SYNC_FROM_YEAR_KEY))
        assertEquals(2015, loadSyncFromYear(db))
        db.close()
    }

    @Test
    fun forget_imported_secret_removes_wallet_secret() {
        val db = createSqliteDatabase(":memory:")
        persistImportedSecret(db, abandon)
        forgetImportedSecret(db)
        assertEquals(WalletSecretInspection.Missing, inspectWalletSecret(db))
        db.close()
    }

    @Test
    fun unknown_year_throws() {
        val db = createSqliteDatabase(":memory:")
        assertFailsWith<IllegalArgumentException> { persistSyncYear(db, 1999) }.also {
            assertTrue(it.message!!.contains("unknown"))
        }
        assertNull(db.keyValue.get(SYNC_FROM_YEAR_KEY))
        db.close()
    }
}
