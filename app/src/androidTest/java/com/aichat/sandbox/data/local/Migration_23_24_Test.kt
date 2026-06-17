package com.aichat.sandbox.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration 23 → 24 (Phase 9: metadata & accessibility helpers): adds the
 * nullable `notes.altText` column holding optional export alt text.
 *
 * Verifies:
 *  - existing note rows survive and default `altText` to NULL, and
 *  - the new column accepts an explicit description on fresh inserts.
 */
@RunWith(AndroidJUnit4::class)
class Migration_23_24_Test {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    private fun insertNoteV23(db: androidx.sqlite.db.SupportSQLiteDatabase, id: String) {
        db.execSQL(
            """
            INSERT INTO notes (
                id, title, backgroundStyle, schemaVersion,
                minX, minY, maxX, maxY,
                thumbnailPath, ocrText, createdAt, updatedAt,
                undoLogJson, notebookId, isIcon,
                viewportOffsetX, viewportOffsetY, viewportScale
            ) VALUES (
                '$id', 'Note', 'graph', 1,
                0.0, 0.0, 100.0, 100.0,
                NULL, NULL, 1000, 2000,
                NULL, NULL, 0,
                NULL, NULL, NULL
            )
            """.trimIndent()
        )
    }

    @Test
    fun migrate23to24_existingNoteDefaultsAltTextToNull() {
        helper.createDatabase(TEST_DB, 23).use { db ->
            insertNoteV23(db, "note-1")
        }

        helper.runMigrationsAndValidate(TEST_DB, 24, true, MIGRATION_23_24).use { migrated ->
            migrated.query("SELECT altText FROM notes WHERE id = 'note-1'").use { cursor ->
                assertTrue("note row should still exist", cursor.moveToFirst())
                assertTrue("legacy notes have no alt text", cursor.isNull(0))
            }
            // New inserts can carry a description.
            migrated.execSQL(
                """
                INSERT INTO notes (
                    id, title, backgroundStyle, schemaVersion,
                    minX, minY, maxX, maxY,
                    thumbnailPath, ocrText, createdAt, updatedAt,
                    undoLogJson, notebookId, isIcon,
                    viewportOffsetX, viewportOffsetY, viewportScale, altText
                ) VALUES (
                    'note-2', 'Note', 'graph', 1,
                    0.0, 0.0, 100.0, 100.0,
                    NULL, NULL, 1000, 2000,
                    NULL, NULL, 0,
                    NULL, NULL, NULL, 'A blue circle.'
                )
                """.trimIndent()
            )
            migrated.query("SELECT altText FROM notes WHERE id = 'note-2'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("A blue circle.", cursor.getString(0))
            }
        }
    }

    @Test
    fun openingV24WithMigration_succeeds() {
        helper.createDatabase(TEST_DB, 23).close()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .addMigrations(MIGRATION_23_24)
            .openHelperFactory(FrameworkSQLiteOpenHelperFactory())
            .build()
        try {
            db.openHelper.writableDatabase.query("SELECT name FROM sqlite_master").close()
        } finally {
            db.close()
            context.deleteDatabase(TEST_DB)
        }
    }

    companion object {
        private const val TEST_DB = "migration-test-23-24"
    }
}
