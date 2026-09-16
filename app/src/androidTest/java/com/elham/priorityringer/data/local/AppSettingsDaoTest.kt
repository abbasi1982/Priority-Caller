package com.elham.priorityringer.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.elham.priorityringer.data.local.dao.SettingsDao
import com.elham.priorityringer.domain.model.DndBypassStrategy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Single-row settings table (Architecture.md § 3, § 10 — "get() always returns
 * row id = 1, prepopulate via callback").
 *
 * "Always returns a row" is the contract that lets every caller treat settings
 * as non-null. If it can ever return null, the null has to be handled in the
 * receiver's `goAsync()` window during an incoming call, which is the worst
 * possible place to discover a missing row.
 *
 * See `DaoTestSupport` for the provisional-names caveat.
 */
@RunWith(AndroidJUnit4::class)
class AppSettingsDaoTest {

    private lateinit var database: PriorityRingerDatabase
    private lateinit var dao: SettingsDao

    @Before
    fun setUp() {
        database = DaoTestSupport.inMemoryDatabase()
        dao = database.settingsDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    /**
     * Uses the **production** `SettingsPrepopulateCallback`, not a copy of it.
     * Replicating the insert inside the test would only assert that the test's
     * own INSERT worked, which is worth nothing.
     */
    @Test
    fun `a_freshly_created_database_already_contains_the_settings_row_with_id_1`() = runTest {
        val prepopulated = DaoTestSupport.inMemoryDatabase(prepopulateSettings = true)
        val settings = try {
            prepopulated.settingsDao().get()
        } finally {
            prepopulated.close()
        }

        assertNotNull(
            "every caller relies on settings being non-null; § 10 requires the row to " +
                "exist from creation",
            settings,
        )
        assertEquals(1, settings?.id)
    }

    @Test
    fun `upserting_the_settings_row_stores_the_new_values`() = runTest {
        dao.upsert(DaoTestSupport.settingsEntity(ringtoneVolumePercent = 80))

        dao.upsert(DaoTestSupport.settingsEntity(ringtoneVolumePercent = 55))

        assertEquals(55, dao.get()?.ringtoneVolumePercent)
    }

    @Test
    fun `upserting_never_creates_a_second_row_because_settings_are_singular`() = runTest {
        dao.upsert(DaoTestSupport.settingsEntity(ringtoneVolumePercent = 80))
        dao.upsert(DaoTestSupport.settingsEntity(ringtoneVolumePercent = 55))
        dao.upsert(DaoTestSupport.settingsEntity(ringtoneVolumePercent = 30))

        assertEquals(1, dao.count())
    }

    @Test
    fun `an_upsert_round_trips_the_escalation_thresholds_unchanged`() = runTest {
        dao.upsert(
            DaoTestSupport.settingsEntity(
                primaryCallCount = 4,
                primaryWindowMinutes = 7,
                secondaryCallCount = 6,
                secondaryWindowMinutes = 21,
            ),
        )

        val stored = dao.get()

        assertEquals(
            listOf(4, 7, 6, 21),
            listOf(
                stored?.escalationPrimaryCallCount,
                stored?.escalationPrimaryWindowMinutes,
                stored?.escalationSecondaryCallCount,
                stored?.escalationSecondaryWindowMinutes,
            ),
        )
    }

    @Test
    fun `an_upsert_round_trips_the_DND_strategy_enum_so_the_converter_is_wired_up`() = runTest {
        dao.upsert(
            DaoTestSupport.settingsEntity(
                dndBypassStrategy = DndBypassStrategy.DISABLE_DND_TEMPORARILY,
            ),
        )

        assertEquals(DndBypassStrategy.DISABLE_DND_TEMPORARILY, dao.get()?.dndBypassStrategy)
    }

    @Test
    fun `an_upsert_round_trips_the_logging_flag_turned_off`() = runTest {
        dao.upsert(DaoTestSupport.settingsEntity(loggingEnabled = false))

        assertEquals(false, dao.get()?.loggingEnabled)
    }
}
