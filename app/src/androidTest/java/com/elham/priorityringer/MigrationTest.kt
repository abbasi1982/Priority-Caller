package com.elham.priorityringer

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elham.priorityringer.data.local.PriorityRingerDatabase
import com.elham.priorityringer.data.local.PriorityRingerDatabaseMigrations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room schema and migration scaffolding (Architecture.md § 10, § 14).
 *
 * Two things are covered:
 *
 *  1. **The schema really is exported.** `MigrationTestHelper` reads
 *     `app/schemas/<database class>/<version>.json` from the androidTest assets
 *     (wired up in `app/build.gradle.kts` via
 *     `androidTest.assets.srcDir("$projectDir/schemas")`). If KSP's
 *     `room.schemaLocation` argument is ever dropped, `createDatabase` fails
 *     here rather than silently leaving future migrations untestable.
 *  2. **The 1 -> 2 migration keeps the user's data.** That is the one that
 *     matters on a real phone: a migration that drops `priority_contacts`
 *     leaves the app installed and looking configured while ringing for
 *     nobody, with no error anywhere.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /**
     * Room 2.6.x constructor taking the database class; the schema folder is
     * derived from it. (Room 2.7+ replaces this with a driver-based
     * constructor — if the Room version is bumped, this line is the one to
     * change.)
     */
    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        instrumentation,
        PriorityRingerDatabase::class.java,
    )

    @Test
    fun `the_version_1_schema_is_exported_and_can_be_created_from_it`() {
        val database = helper.createDatabase(TEST_DB, 1)

        assertTrue(
            "a database created from the exported v1 schema must be open and usable",
            database.isOpen,
        )
        database.close()
    }

    @Test
    fun `the_version_2_schema_is_exported_and_can_be_created_from_it`() {
        val database = helper.createDatabase(TEST_DB, 2)

        assertTrue(database.isOpen)
        database.close()
    }

    @Test
    fun `data_written_at_version_1_survives_the_migration_to_version_2`() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                "INSERT INTO priority_contacts " +
                    "(displayName, originalInput, matchKey, e164, enabled, createdAtEpochMs) " +
                    "VALUES ('Mum', '+15551234567', '1234567', '+15551234567', 1, 0)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DB,
            2,
            true,
            PriorityRingerDatabaseMigrations.MIGRATION_1_2,
        )

        // runMigrationsAndValidate checks the schema itself. Only the data
        // needs asserting here.
        migrated.query("SELECT displayName FROM priority_contacts").use { cursor ->
            assertTrue("the contact must survive the migration", cursor.moveToFirst())
            assertEquals("Mum", cursor.getString(0))
        }
        migrated.close()
    }

    @Test
    fun `the_new_column_starts_null_meaning_never_observed`() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                "INSERT INTO app_settings (id, ringtoneVolumePercent, escalationEnabled, " +
                    "escalationPrimaryCallCount, escalationPrimaryWindowMinutes, " +
                    "escalationSecondaryCallCount, escalationSecondaryWindowMinutes, " +
                    "autoRestoreTimeoutSeconds, loggingEnabled, dndBypassStrategy) " +
                    "VALUES (1, 80, 1, 2, 5, 3, 10, 90, 1, 'PRIORITY_ALLOW_CALLS')",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DB,
            2,
            true,
            PriorityRingerDatabaseMigrations.MIGRATION_1_2,
        )

        // NULL is load-bearing: it means "the app has never seen this phone
        // audible", which restore treats differently from any volume value.
        migrated.query("SELECT lastAudibleRingIndex FROM app_settings WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(
                "an upgraded install must not start with a fabricated ring level",
                cursor.isNull(0),
            )
        }
        migrated.close()
    }

    private companion object {
        const val TEST_DB = "migration-test"
    }
}
