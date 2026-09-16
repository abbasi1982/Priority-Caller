package com.elham.priorityringer

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elham.priorityringer.data.local.PriorityRingerDatabase
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room schema and migration scaffolding (Architecture.md § 10, § 14).
 *
 * At version 1 there is nothing to migrate yet, so this file does two things:
 *
 *  1. **Proves the schema is actually exported.** `MigrationTestHelper` reads
 *     `app/schemas/<database class>/1.json` from the androidTest assets
 *     (wired up in `app/build.gradle.kts` via
 *     `androidTest.assets.srcDir("$projectDir/schemas")`). If KSP's
 *     `room.schemaLocation` argument is ever dropped, `createDatabase` fails
 *     here rather than silently leaving future migrations untestable — which is
 *     the failure mode this test really guards against.
 *  2. **Leaves the v1 → v2 case ready to switch on**, so adding a migration is
 *     an edit rather than a new piece of test infrastructure.
 *
 * ---------------------------------------------------------------------------
 * PROVISIONAL: `PriorityRingerDatabase` and its package are inferred from
 * ImplementationPlan.md Phase 1; see `data/local/DaoTestSupport.kt`. This test
 * cannot pass until the Room layer exists **and** a build has exported
 * `app/schemas/…/1.json` (that directory is currently empty).
 * ---------------------------------------------------------------------------
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

    // -----------------------------------------------------------------------
    // Ready for version 2
    // -----------------------------------------------------------------------
    //
    // When PriorityRingerDatabase moves to version 2, define the migration in
    // data/local (e.g. `PriorityRingerDatabaseMigrations.MIGRATION_1_2`), then enable:
    //
    // @Test
    // fun `data_written_at_version_1_survives_the_migration_to_version_2`() {
    //     helper.createDatabase(TEST_DB, 1).apply {
    //         execSQL(
    //             "INSERT INTO priority_contacts " +
    //                 "(display_name, original_input, match_key, e164, enabled, created_at) " +
    //                 "VALUES ('Mum', '+15551234567', '1234567', '+15551234567', 1, 0)",
    //         )
    //         close()
    //     }
    //
    //     val migrated = helper.runMigrationsAndValidate(
    //         TEST_DB,
    //         2,
    //         true,
    //         PriorityRingerDatabaseMigrations.MIGRATION_1_2,
    //     )
    //
    //     // MigrationTestHelper validates the schema itself; only the data
    //     // needs asserting here — a migration that drops the user's priority
    //     // contacts would make the app silently inert after an update.
    //     migrated.query("SELECT COUNT(*) FROM priority_contacts").use { cursor ->
    //         cursor.moveToFirst()
    //         assertEquals(1, cursor.getInt(0))
    //     }
    //     migrated.close()
    // }

    private companion object {
        const val TEST_DB = "migration-test"
    }
}
