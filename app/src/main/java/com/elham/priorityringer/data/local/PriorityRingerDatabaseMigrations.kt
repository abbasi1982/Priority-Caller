package com.elham.priorityringer.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema migrations (Architecture.md § 10).
 *
 * Migrations are written by hand and never replaced with
 * `fallbackToDestructiveMigration`. Destructive fallback would silently delete
 * the user's priority contacts on an update, leaving the app installed,
 * looking configured, and ringing for nobody — the exact failure this app
 * cannot have, arriving without a single error message.
 */
object PriorityRingerDatabaseMigrations {

    /**
     * Adds `app_settings.lastAudibleRingIndex`.
     *
     * Nullable with no default, and that is the point: `NULL` means "the app
     * has never seen this phone audible", which is a different thing from any
     * particular volume. Restore checks for it and leaves the level alone
     * rather than writing a guess.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE app_settings ADD COLUMN lastAudibleRingIndex INTEGER")
        }
    }

    /** Every migration, in order, for the Room builder. */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
