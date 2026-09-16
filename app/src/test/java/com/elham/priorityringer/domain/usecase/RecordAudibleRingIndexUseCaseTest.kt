package com.elham.priorityringer.domain.usecase

import com.elham.priorityringer.domain.model.RingerMode
import com.elham.priorityringer.fake.FakeAudioPort
import com.elham.priorityringer.fake.FakeRestoreRepository
import com.elham.priorityringer.fake.FakeSettingsRepository
import com.elham.priorityringer.fake.testSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Learning the user's own ring level.
 *
 * The risk here is not failing to learn — it is learning the **wrong** number.
 * A missing observation makes restore leave the level alone, which is merely
 * unhelpful. A wrong observation makes restore confidently write a level the
 * user never chose, which is the app changing their phone behind their back.
 *
 * So most of these tests are about when it refuses.
 */
class RecordAudibleRingIndexUseCaseTest {

    private val audio = FakeAudioPort()
    private val settings = FakeSettingsRepository()
    private val restoreRepository = FakeRestoreRepository()

    private val useCase = RecordAudibleRingIndexUseCase(
        audio = audio,
        settings = settings,
        restoreRepository = restoreRepository,
    )

    @Test
    fun `an audible level on a normal phone is recorded`() = runTest {
        audio.ringerMode = RingerMode.NORMAL
        audio.currentVolumeIndex = 6

        useCase()

        assertEquals(listOf(6), settings.recordedAudibleIndices)
    }

    @Test
    fun `nothing is recorded while a restore is pending, because the level is ours`() = runTest {
        // Mid-priority-call: apply has raised the ringer to NORMAL at high
        // volume. Opening the app right now must not teach it that the user's
        // preferred level is the one the app just set — that would make the
        // side effect permanent and self-justifying.
        audio.ringerMode = RingerMode.NORMAL
        audio.currentVolumeIndex = 13
        restoreRepository.seed(testSnapshot(ringerMode = RingerMode.SILENT))

        useCase()

        assertEquals(emptyList<Int>(), settings.recordedAudibleIndices)
    }

    @Test
    fun `nothing is recorded in silent, where the index means nothing`() = runTest {
        audio.ringerMode = RingerMode.SILENT
        audio.currentVolumeIndex = 0

        useCase()

        assertEquals(emptyList<Int>(), settings.recordedAudibleIndices)
    }

    @Test
    fun `nothing is recorded in vibrate`() = runTest {
        audio.ringerMode = RingerMode.VIBRATE
        audio.currentVolumeIndex = 0

        useCase()

        assertEquals(emptyList<Int>(), settings.recordedAudibleIndices)
    }

    @Test
    fun `a zero index is not an audible level`() = runTest {
        audio.ringerMode = RingerMode.NORMAL
        audio.currentVolumeIndex = 0

        useCase()

        assertEquals(emptyList<Int>(), settings.recordedAudibleIndices)
    }

    @Test
    fun `a later observation replaces an earlier one`() = runTest {
        audio.ringerMode = RingerMode.NORMAL
        audio.currentVolumeIndex = 4
        useCase()

        audio.currentVolumeIndex = 9
        useCase()

        assertEquals(listOf(4, 9), settings.recordedAudibleIndices)
        assertEquals(9, settings.get().lastAudibleRingIndex)
    }
}
