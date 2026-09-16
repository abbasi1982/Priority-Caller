package com.elham.priorityringer.di

import android.content.Context
import androidx.room.Room
import com.elham.priorityringer.data.local.PriorityRingerDatabase
import com.elham.priorityringer.data.local.SettingsPrepopulateCallback
import com.elham.priorityringer.data.local.dao.AuditDao
import com.elham.priorityringer.data.local.dao.ContactDao
import com.elham.priorityringer.data.local.dao.EscalationDao
import com.elham.priorityringer.data.local.dao.RestoreDao
import com.elham.priorityringer.data.local.dao.SettingsDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Architecture.md § 11 — `DatabaseModule`: Room DB and DAOs. */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * No `fallbackToDestructiveMigration`.
     *
     * A destructive fallback would silently discard the user's priority
     * contacts on a schema change — and, worse, a `pending_restore` row, which
     * is the only record of how to put the device back (§ A.3). Version 1 has
     * no migrations yet; adding them is the correct cost of a schema change.
     */
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): PriorityRingerDatabase = Room.databaseBuilder(
        context,
        PriorityRingerDatabase::class.java,
        PriorityRingerDatabase.NAME,
    )
        .addCallback(SettingsPrepopulateCallback())
        .build()

    @Provides
    @Singleton
    fun provideContactDao(db: PriorityRingerDatabase): ContactDao = db.contactDao()

    @Provides
    @Singleton
    fun provideAuditDao(db: PriorityRingerDatabase): AuditDao = db.auditDao()

    @Provides
    @Singleton
    fun provideSettingsDao(db: PriorityRingerDatabase): SettingsDao = db.settingsDao()

    @Provides
    @Singleton
    fun provideRestoreDao(db: PriorityRingerDatabase): RestoreDao = db.restoreDao()

    @Provides
    @Singleton
    fun provideEscalationDao(db: PriorityRingerDatabase): EscalationDao = db.escalationDao()
}
