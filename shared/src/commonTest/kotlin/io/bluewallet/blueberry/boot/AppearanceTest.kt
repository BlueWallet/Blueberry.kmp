package io.bluewallet.blueberry.boot

import io.bluewallet.blueberry.storage.createSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppearanceTest {
    @Test
    fun missing_or_invalid_kv_loads_as_system() {
        val db = createSqliteDatabase(":memory:")
        assertEquals(AppearancePreference.System, loadAppearance(db))
        assertNull(db.keyValue.get(APPEARANCE_KEY))

        db.keyValue.set(APPEARANCE_KEY, "nope")
        assertEquals(AppearancePreference.System, loadAppearance(db))
        db.keyValue.set(APPEARANCE_KEY, "")
        assertEquals(AppearancePreference.System, loadAppearance(db))
        db.keyValue.set(APPEARANCE_KEY, "1")
        assertEquals(AppearancePreference.System, loadAppearance(db))
        db.close()
    }

    @Test
    fun save_load_round_trip() {
        val db = createSqliteDatabase(":memory:")
        saveAppearance(db, AppearancePreference.Light)
        assertEquals("light", db.keyValue.get(APPEARANCE_KEY))
        assertEquals(AppearancePreference.Light, loadAppearance(db))

        saveAppearance(db, AppearancePreference.Dark)
        assertEquals("dark", db.keyValue.get(APPEARANCE_KEY))
        assertEquals(AppearancePreference.Dark, loadAppearance(db))

        saveAppearance(db, AppearancePreference.System)
        assertEquals("system", db.keyValue.get(APPEARANCE_KEY))
        assertEquals(AppearancePreference.System, loadAppearance(db))
        db.close()
    }

    @Test
    fun resolve_dark_follows_system_unless_forced() {
        assertTrue(resolveDarkTheme(AppearancePreference.System, systemDark = true))
        assertFalse(resolveDarkTheme(AppearancePreference.System, systemDark = false))
        assertFalse(resolveDarkTheme(AppearancePreference.Light, systemDark = true))
        assertFalse(resolveDarkTheme(AppearancePreference.Light, systemDark = false))
        assertTrue(resolveDarkTheme(AppearancePreference.Dark, systemDark = false))
        assertTrue(resolveDarkTheme(AppearancePreference.Dark, systemDark = true))
    }

    @Test
    fun cycle_system_light_dark() {
        assertEquals(
            AppearancePreference.Light,
            cycleAppearance(AppearancePreference.System),
        )
        assertEquals(
            AppearancePreference.Dark,
            cycleAppearance(AppearancePreference.Light),
        )
        assertEquals(
            AppearancePreference.System,
            cycleAppearance(AppearancePreference.Dark),
        )
    }

    @Test
    fun labels_are_follow_system_light_dark() {
        assertEquals("Follow system", appearanceLabel(AppearancePreference.System))
        assertEquals("Light", appearanceLabel(AppearancePreference.Light))
        assertEquals("Dark", appearanceLabel(AppearancePreference.Dark))
    }
}
