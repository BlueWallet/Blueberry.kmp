package io.bluewallet.blueberry.boot

import io.bluewallet.blueberry.storage.Database

const val APPEARANCE_KEY = "appearance"

enum class AppearancePreference {
    System,
    Light,
    Dark,
}

fun loadAppearance(db: Database): AppearancePreference =
    when (db.keyValue.get(APPEARANCE_KEY)) {
        "light" -> AppearancePreference.Light
        "dark" -> AppearancePreference.Dark
        else -> AppearancePreference.System
    }

fun saveAppearance(
    db: Database,
    preference: AppearancePreference,
) {
    db.keyValue.set(
        APPEARANCE_KEY,
        when (preference) {
            AppearancePreference.System -> "system"
            AppearancePreference.Light -> "light"
            AppearancePreference.Dark -> "dark"
        },
    )
}

fun resolveDarkTheme(
    preference: AppearancePreference,
    systemDark: Boolean,
): Boolean =
    when (preference) {
        AppearancePreference.System -> systemDark
        AppearancePreference.Light -> false
        AppearancePreference.Dark -> true
    }

fun cycleAppearance(preference: AppearancePreference): AppearancePreference =
    when (preference) {
        AppearancePreference.System -> AppearancePreference.Light
        AppearancePreference.Light -> AppearancePreference.Dark
        AppearancePreference.Dark -> AppearancePreference.System
    }

fun appearanceLabel(preference: AppearancePreference): String =
    when (preference) {
        AppearancePreference.System -> "Follow system"
        AppearancePreference.Light -> "Light"
        AppearancePreference.Dark -> "Dark"
    }
