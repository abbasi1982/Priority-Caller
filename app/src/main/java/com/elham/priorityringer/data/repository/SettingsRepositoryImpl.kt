package com.elham.priorityringer.data.repository

import com.elham.priorityringer.data.local.dao.SettingsDao
import com.elham.priorityringer.data.local.mapper.toDomain
import com.elham.priorityringer.data.local.mapper.toEntity
import com.elham.priorityringer.di.IoDispatcher
import com.elham.priorityringer.domain.model.AppSettings
import com.elham.priorityringer.domain.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Architecture.md § 10 — single row, id = 1, prepopulated by the database
 * callback.
 *
 * Both reads still fall back to `AppSettings()` on a null row. The callback
 * only runs when the database file is created, so a row deleted by hand, or a
 * future migration that adds the table, must not be able to make the app read
 * "no settings" as a crash.
 */
@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val dao: SettingsDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) : SettingsRepository {

    override fun observe(): Flow<AppSettings> =
        dao.observe().map { it?.toDomain() ?: AppSettings() }.flowOn(io)

    override suspend fun get(): AppSettings =
        withContext(io) { dao.get()?.toDomain() ?: AppSettings() }

    /**
     * Clamped via [AppSettings.validated] before writing, so out-of-range
     * values cannot reach storage at all — a ringtone volume of 0 persisted
     * once would leave the app permanently armed but silent.
     */
    override suspend fun update(settings: AppSettings) = withContext(io) {
        dao.upsert(settings.validated().toEntity())
    }
}
