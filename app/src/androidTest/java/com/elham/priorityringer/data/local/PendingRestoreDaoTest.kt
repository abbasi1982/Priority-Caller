package com.elham.priorityringer.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.elham.priorityringer.data.local.dao.RestoreDao
import com.elham.priorityringer.domain.model.InterruptionFilter
import com.elham.priorityringer.domain.model.RingerMode
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Durable pre-mutation snapshot (Architecture.md § A.3).
 *
 * This single row is what makes § 17.5 ("restore is guaranteed by call-state
 * **and** timeout") true across process death. Its one non-obvious rule is
 * **insert-if-absent**: a second overlapping call must not overwrite the
 * genuine pre-mutation state with values the app itself has already written.
 * If it did, restore would faithfully put the phone back into the loud,
 * DND-relaxed state it was in mid-call — the exact failure the whole mechanism
 * exists to prevent.
 *
 * See `DaoTestSupport` for the provisional-names caveat.
 */
@RunWith(AndroidJUnit4::class)
class PendingRestoreDaoTest {

    private lateinit var database: PriorityRingerDatabase
    private lateinit var dao: RestoreDao

    @Before
    fun setUp() {
        database = DaoTestSupport.inMemoryDatabase()
        dao = database.restoreDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `there_is_no_pending_snapshot_on_a_fresh_database`() = runTest {
        assertNull(dao.getPending())
    }

    @Test
    fun `the_first_insert_stores_the_snapshot`() = runTest {
        dao.insertIfAbsent(
            DaoTestSupport.pendingRestoreEntity(ringerMode = RingerMode.VIBRATE),
        )

        assertNotNull(dao.getPending())
    }

    @Test
    fun `the_first_insert_reports_that_it_took_effect`() = runTest {
        val rowId = dao.insertIfAbsent(DaoTestSupport.pendingRestoreEntity())

        assertTrue("an accepted insert must not report the -1 conflict sentinel", rowId > 0L)
    }

    @Test
    fun `a_second_insert_does_not_overwrite_the_row_an_overlapping_call_already_wrote`() = runTest {
        dao.insertIfAbsent(
            DaoTestSupport.pendingRestoreEntity(
                ringerMode = RingerMode.VIBRATE,
                ringVolumeCurrent = 4,
            ),
        )

        dao.insertIfAbsent(
            DaoTestSupport.pendingRestoreEntity(
                // Already-mutated values — what the *second* call would capture.
                ringerMode = RingerMode.NORMAL,
                ringVolumeCurrent = 15,
            ),
        )

        assertEquals(
            "overwriting would make restore put the phone back into the state the app " +
                "itself created, leaving it permanently loud",
            RingerMode.VIBRATE,
            dao.getPending()?.ringerMode,
        )
    }

    @Test
    fun `a_second_insert_leaves_the_original_volume_untouched`() = runTest {
        dao.insertIfAbsent(DaoTestSupport.pendingRestoreEntity(ringVolumeCurrent = 4))

        dao.insertIfAbsent(DaoTestSupport.pendingRestoreEntity(ringVolumeCurrent = 15))

        assertEquals(4, dao.getPending()?.ringVolumeCurrent)
    }

    @Test
    fun `a_rejected_second_insert_reports_minus_one_rather_than_throwing`() = runTest {
        dao.insertIfAbsent(DaoTestSupport.pendingRestoreEntity())

        val rowId = dao.insertIfAbsent(DaoTestSupport.pendingRestoreEntity())

        assertEquals(
            "the repository turns this into saveIfAbsent() == false; an exception in " +
                "the goAsync window would be far worse",
            -1L,
            rowId,
        )
    }

    @Test
    fun `only_ever_one_row_exists_because_the_snapshot_is_singular`() = runTest {
        dao.insertIfAbsent(DaoTestSupport.pendingRestoreEntity())
        dao.insertIfAbsent(DaoTestSupport.pendingRestoreEntity())
        dao.insertIfAbsent(DaoTestSupport.pendingRestoreEntity())

        assertEquals(1, dao.count())
    }

    @Test
    fun `clearing_removes_the_snapshot_so_the_next_call_can_take_a_fresh_one`() = runTest {
        dao.insertIfAbsent(DaoTestSupport.pendingRestoreEntity())

        dao.clear()

        assertNull(dao.getPending())
    }

    @Test
    fun `after_clearing_a_new_snapshot_can_be_written`() = runTest {
        dao.insertIfAbsent(DaoTestSupport.pendingRestoreEntity(ringerMode = RingerMode.VIBRATE))
        dao.clear()

        dao.insertIfAbsent(DaoTestSupport.pendingRestoreEntity(ringerMode = RingerMode.SILENT))

        assertEquals(RingerMode.SILENT, dao.getPending()?.ringerMode)
    }

    @Test
    fun `the_interruption_filter_and_zen_rule_id_survive_the_round_trip`() = runTest {
        dao.insertIfAbsent(
            DaoTestSupport.pendingRestoreEntity(
                interruptionFilter = InterruptionFilter.ALARMS,
                zenRuleId = "rule-7",
            ),
        )

        val stored = dao.getPending()

        assertEquals(InterruptionFilter.ALARMS, stored?.interruptionFilter)
        assertEquals("rule-7", stored?.zenRuleId)
    }

    @Test
    fun `a_null_zen_rule_id_round_trips_as_null_because_the_legacy_branch_never_creates_one`() =
        runTest {
            dao.insertIfAbsent(DaoTestSupport.pendingRestoreEntity(zenRuleId = null))

            assertNull(dao.getPending()?.zenRuleId)
        }
}
