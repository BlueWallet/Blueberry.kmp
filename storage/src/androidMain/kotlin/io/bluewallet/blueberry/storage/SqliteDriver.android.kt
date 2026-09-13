package io.bluewallet.blueberry.storage

import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.Properties

internal actual fun openSqliteDriver(path: String): SqlDriver {
    if (!isAndroidFrameworkSqliteAvailable()) {
        return openJdbcSqliteDriver(path)
    }
    return AndroidSqliteDriver(PathSqliteOpenHelper(path))
}

private val androidFrameworkSqliteAvailable: Boolean by lazy {
    try {
        SQLiteDatabase.create(null).close()
        true
    } catch (e: RuntimeException) {
        if (e.message?.contains("not mocked") == true) false else throw e
    }
}

private fun isAndroidFrameworkSqliteAvailable(): Boolean = androidFrameworkSqliteAvailable

private fun openJdbcSqliteDriver(path: String): SqlDriver {
    try {
        if (path == ":memory:") {
            val properties =
                Properties().apply {
                    setProperty("journal_mode", "WAL")
                    setProperty("synchronous", "NORMAL")
                    setProperty("wal_autocheckpoint", "10000")
                }
            return JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, properties)
        }
        return JdbcSqliteDriver(
            "jdbc:sqlite:$path?journal_mode=WAL&synchronous=NORMAL&wal_autocheckpoint=10000",
        )
    } catch (e: NoClassDefFoundError) {
        throw IllegalStateException(
            "Android SQLite is unavailable and JdbcSqliteDriver is not on the classpath.",
            e,
        )
    }
}

private class PathSqliteOpenHelper(
    private val path: String,
) : SupportSQLiteOpenHelper {
    private var database: SupportSQLiteDatabase? = null
    private var writeAheadLoggingEnabled = false

    override val databaseName: String? = path

    override fun setWriteAheadLoggingEnabled(enabled: Boolean) {
        writeAheadLoggingEnabled = enabled
        database?.let { db ->
            if (enabled) {
                db.enableWriteAheadLogging()
            } else {
                db.disableWriteAheadLogging()
            }
        }
    }

    override val writableDatabase: SupportSQLiteDatabase
        get() = database ?: openDatabase().also { database = it }

    override val readableDatabase: SupportSQLiteDatabase
        get() = writableDatabase

    override fun close() {
        database?.close()
        database = null
    }

    private fun openDatabase(): SupportSQLiteDatabase {
        val sqlite =
            if (path == ":memory:") {
                SQLiteDatabase.create(null)
            } else {
                SQLiteDatabase.openOrCreateDatabase(path, null)
            }
        val db = wrapFrameworkDatabase(sqlite)
        if (writeAheadLoggingEnabled) {
            db.enableWriteAheadLogging()
        }
        return db
    }
}

private fun wrapFrameworkDatabase(sqlite: SQLiteDatabase): SupportSQLiteDatabase {
    val className = "androidx.sqlite.db.framework.FrameworkSQLiteDatabase"
    val clazz =
        try {
            Class.forName(className)
        } catch (_: ClassNotFoundException) {
            throw IllegalStateException(
                "$className: AndroidX SQLite framework constructor could not be found.",
            )
        }
    val ctor =
        try {
            clazz.getDeclaredConstructor(SQLiteDatabase::class.java)
        } catch (_: ReflectiveOperationException) {
            throw IllegalStateException(
                "$className: AndroidX SQLite framework constructor could not be found.",
            )
        }
    ctor.isAccessible = true
    return try {
        @Suppress("UNCHECKED_CAST")
        ctor.newInstance(sqlite) as SupportSQLiteDatabase
    } catch (e: ReflectiveOperationException) {
        throw IllegalStateException(
            "$className: AndroidX SQLite framework constructor could not be found.",
            e,
        )
    }
}
