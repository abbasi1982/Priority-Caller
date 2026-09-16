package com.elham.priorityringer.domain.usecase

import com.elham.priorityringer.domain.model.RingerMode
import com.elham.priorityringer.domain.port.AudioPort
import com.elham.priorityringer.domain.repository.RestoreRepository
import com.elham.priorityringer.domain.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Remember the ring level the user actually chose, while it is still visible.
 *
 * ## Why this has to exist
 *
 * Android has no supported way to read the audible ring level while the phone
 * is silent. `getStreamVolume(STREAM_RING)` reports 0, and the platform's own
 * "last audible index" is `@hide` — off limits under this project's
 * constraints. So the level has to be *observed while the phone is audible* and
 * remembered.
 *
 * Without it the app has a lasting side effect on a phone that lives on Silent.
 * Apply raises the ring to an audible level for the call; the system records
 * that as the last audible level; restore puts the phone back to Silent but has
 * no idea what the level was before. The next time the user switches their
 * ringer back to Normal by hand — hours or days later, with no connection to
 * any call — it is at the app's volume, not theirs.
 *
 * ## When it declines to record
 *
 * Recording the wrong number is worse than recording nothing, because restore
 * would then confidently write a level the user never chose. So this refuses
 * unless the reading is trustworthy:
 *
 * - **A restore is pending.** The app is mid-call and the current level is the
 *   app's own doing. This is the important one: without it, opening the app
 *   during a priority call would record the raised volume as the user's
 *   preference and make the bug permanent.
 * - **The ringer is not NORMAL.** The index is forced to 0 and means nothing.
 * - **The index is 0 or unreadable.** Not an audible level by definition.
 */
@Singleton
class RecordAudibleRingIndexUseCase @Inject constructor(
    private val audio: AudioPort,
    private val settings: SettingsRepository,
    private val restoreRepository: RestoreRepository,
) {

    suspend operator fun invoke() {
        if (restoreRepository.getPending() != null) {
            Timber.d("Not recording ring index: a restore is pending, so this level is ours")
            return
        }

        if (audio.currentRingerMode() != RingerMode.NORMAL) return

        val index = audio.currentRingVolume().current
        if (index <= 0) return

        runCatching { settings.recordAudibleRingIndex(index) }
            .onFailure { Timber.w(it, "Could not record audible ring index") }
    }
}
