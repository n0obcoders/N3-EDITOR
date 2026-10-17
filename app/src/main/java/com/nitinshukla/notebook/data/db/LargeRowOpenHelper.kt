package com.nitinshukla.notebook.data.db

import android.database.AbstractWindowedCursor
import android.database.Cursor
import android.database.CursorWindow
import android.os.Build
import android.os.CancellationSignal
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory

/**
 * Android reads query results through a CursorWindow of 2 MB. A note bigger than that (a pasted
 * log, an opened document) could be saved but never read back: restoring a backup, building one,
 * or opening the note failed with "Row too big to fit into CursorWindow". Every cursor this
 * helper hands to Room gets a window large enough for such rows.
 */
class LargeRowOpenHelperFactory(
    private val delegate: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory(),
) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper =
        LargeRowOpenHelper(delegate.create(configuration))

    companion object {
        /** 16x the platform default: room for notes of many megabytes. The memory is reserved, not used, until rows are read. */
        const val WINDOW_BYTES = 32L * 1024 * 1024

        /**
         * Before Android 9 a window's size cannot be chosen per cursor; raise the process-wide
         * default instead. Harmless if the platform does not allow it.
         */
        fun raiseDefaultWindowSize() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return
            runCatching {
                CursorWindow::class.java.getDeclaredField("sCursorWindowSize").apply {
                    isAccessible = true
                    setInt(null, WINDOW_BYTES.toInt())
                }
            }
        }

        /** Cursors handed out so far; lets tests check that Room reads through this helper. */
        @Volatile internal var cursorsWidened = 0L

        internal fun widen(cursor: Cursor): Cursor {
            cursorsWidened++
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && cursor is AbstractWindowedCursor) {
                runCatching { cursor.window = CursorWindow(null, WINDOW_BYTES) }
            }
            return cursor
        }
    }
}

private class LargeRowOpenHelper(private val delegate: SupportSQLiteOpenHelper) : SupportSQLiteOpenHelper by delegate {
    override val writableDatabase: SupportSQLiteDatabase get() = LargeRowDatabase(delegate.writableDatabase)
    override val readableDatabase: SupportSQLiteDatabase get() = LargeRowDatabase(delegate.readableDatabase)
}

private class LargeRowDatabase(private val delegate: SupportSQLiteDatabase) : SupportSQLiteDatabase by delegate {
    override fun query(query: String): Cursor = LargeRowOpenHelperFactory.widen(delegate.query(query))
    override fun query(query: String, bindArgs: Array<out Any?>): Cursor = LargeRowOpenHelperFactory.widen(delegate.query(query, bindArgs))
    override fun query(query: SupportSQLiteQuery): Cursor = LargeRowOpenHelperFactory.widen(delegate.query(query))
    override fun query(query: SupportSQLiteQuery, cancellationSignal: CancellationSignal?): Cursor =
        LargeRowOpenHelperFactory.widen(delegate.query(query, cancellationSignal))
}
