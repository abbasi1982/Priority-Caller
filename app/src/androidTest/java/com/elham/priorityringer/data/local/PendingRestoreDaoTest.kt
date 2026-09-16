package com.elham.priorityringer.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
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
    fun `there is no pending snapshot on a fresh database`() = runTest {
        assertNull(dao.getPending())
    }

    @Test
    fun `the first insert stores the snapshot`() = runTest {
        dao.insertIfAbsent(
            DaoTestSupport.pendingRestoreEntity(ringerMode = RingerMode.VIBRATE),
        )

        assertNotNull(dao.getPending())
    }

    @Test
    fun `the first insert reports that it took effect`() = runTest {
        val rowId = dao.insertIfAbsent(DaoTestSupport.pendingRestoreEntity())

        assertTrue("an accepted insert must not report the -1 conflict sentinel", rowId > 0L)
    }

    @Test
    fun `a second insert does not overwrite the row an overlapping call already wrote`() = runTest {
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
    fun `a second insert leaves the original volume untouched`() = runTest {
        dao.insertIfAbsent(DaoTestSupport.pendingRestoreEntity(ringVolumeCurrent = 4))

        dao.insertIfAbsent(DaoTestSupport.pendingRestoreEntity(ringVolumeCurrent = 15))

        assertEquals(4, dao.getPending()?.ringVolumeCurrent)
    }

    @Test
    fun `a rejected second insert reports minus one rather than throwing`() = runTest {
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
    fun `only ever one row exists, because the snapshot is singular`() = runTest {
        dao.insertIfAbsent(DaoTestSupport.pendingRestoreEntity())
        dao.insertIfAbsent(DaoTestSupport.pendingRestoreEntity())
        dao.insertIfAbsent(DaoTestSupport.pendingRestoreEntity())

        assertEquals(1, dao.count())
    }

    @Test
    fun `clearing removes the snapshot so the next call can take a fresh one`() = runTest {
        dao.insertIfAbsent(DaoTestSupport.pendingRestoreEntity())

        dao.clear()

        assertNull(dao.getPending())
    }

    @Test
    fun `after clearing, a new snapshot can be written`() = runTest {
        dao.insertIfAbsent(DaoTestSupport.pendingRestoreEntity(ringerMode = RingerMode.VIBRATE))
        dao.clear()

        dao.insertIfAbsent(DaoTestSupport.pendingRestoreEntity(ringerMode = RingerMode.SILENT))

        assertEquals(RingerMode.SILENT, dao.getPending()?.ringerMode)
    }

    @Test
    fun `the interruption filter and zen rule id survive the round trip`() = runTest {
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
    fun `a null zen rule id round-trips as null, because the legacy branch never creates one`() =
        runTest {
            dao.insertIfAbsent(DaoTestSupport.pendingRestoreEntity(zenRuleId = null))

            assertNull(dao.getPending()?.zenRuleId)
        }
}
