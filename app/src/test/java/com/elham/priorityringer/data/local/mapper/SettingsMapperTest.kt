package com.elham.priorityringer.data.local.mapper

import com.elham.priorityringer.domain.model.AppSettings
import com.elham.priorityringer.domain.model.DndBypassStrategy
import com.elham.priorityringer.domain.model.EscalationThresholds
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The settings mapper, and one bug it is here to keep fixed.
 *
 * `SettingsRepository.update` writes through Room's `@Upsert`, which replaces
 * the entire row. That makes [AppSettings.toEntity] the only thing standing
 * between a settings edit and the loss of every field it forgets to copy — an
 * omission there does not leave a column alone, it overwrites it with the
 * entity's default.
 *
 * That is not hypothetical. `lastAudibleRingIndex` was missing from `toEntity`,
 * so every settings change wiped the learned audible ring level back to NULL
 * and the next Silent restore had nothing to put the volume back to. It never
 * crashed and never wrote a wrong value — restore declines on a null index and
 * leaves the level alone — so it simply looked like the feature not working.
 *
 * **No fake could have caught it.** `FakeSettingsRepository` models `update`
 * as a whole-object replace, which is what the repository *means*, not what
 * the mapper *does*. A round-trip through the real mapper is the only place
 * this is visible without a device.
 */
class SettingsMapperTest {

    /**
     * Deliberately every field non-default. A round-trip test built on default
     * values passes even when the mapper drops a field, because the default is
     * what a dropped field lands on.
     */
    private val settings = AppSettings(
        ringtoneVolumePercent = 65,
        escalation = EscalationThresholds(
            enabled = false,
            primaryCallCount = 4,
            primaryWindowMinutes = 7,
            secondaryCallCount = 6,
            secondaryWindowMinutes = 21,
        ),
        autoRestoreTimeoutSeconds = 120,
        loggingEnabled = false,
        dndBypassStrategy = DndBypassStrategy.DISABLE_DND_TEMPORARILY,
        lastAudibleRingIndex = 9,
    )

    @Test
    fun `a full round trip changes nothing`() {
        assertEquals(settings, settings.toEntity().toDomain())
    }

    @Test
    fun `the learned audible ring level survives being written`() {
        assertEquals(
            "an @Upsert writes this row wholesale, so a field the mapper drops " +
                "is a field the next settings edit destroys",
            9,
            settings.toEntity().lastAudibleRingIndex,
        )
    }

    @Test
    fun `a never-observed audible level stays null rather than becoming a number`() {
        // NULL is load-bearing: it means "the app has never seen this phone
        // audible", which restore treats differently from any index.
        val neverObserved = settings.copy(lastAudibleRingIndex = null)

        assertEquals(null, neverObserved.toEntity().lastAudibleRingIndex)
    }

    @Test
    fun `the escalation thresholds survive the flattening into columns`() {
        val entity = settings.toEntity()

        assertEquals(false, entity.escalationEnabled)
        assertEquals(4, entity.escalationPrimaryCallCount)
        assertEquals(7, entity.escalationPrimaryWindowMinutes)
        assertEquals(6, entity.escalationSecondaryCallCount)
        assertEquals(21, entity.escalationSecondaryWindowMinutes)
    }
}
