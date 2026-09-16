package com.elham.priorityringer.data.platform.audio

import android.media.AudioManager
import com.elham.priorityringer.domain.model.FailureReason
import com.elham.priorityringer.domain.model.Outcome
import com.elham.priorityringer.domain.model.RingerMode
import com.elham.priorityringer.domain.model.VolumeSnapshot
import com.elham.priorityringer.domain.port.AudioPort
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * `AudioManager` implementation of [AudioPort] (FR4).
 *
 * The discipline this class exists to enforce, from Architecture.md § 6.2 and
 * § 7.5: **never report success without reading the value back.**
 *
 * `AudioManager.setRingerMode` and `setStreamVolume` are `void`. They do not
 * report refusal. On several OEM skins they return normally having changed
 * nothing — Samsung and Xiaomi layers are the usual suspects (§ 7). An
 * implementation that trusted the absence of an exception would cheerfully tell
 * the user their phone was about to ring loudly when it was not.
 *
 * So every mutation here is: pre-check → attempt → re-read → compare. A
 * mismatch becomes [FailureReason.VERIFICATION_FAILED] (or the more specific
 * [FailureReason.SILENT_NOT_OVERRIDDEN]), which the audit log and UI surface as
 * a *detected* failure. Per § 7, that is where it stops: a detected failure is
 * never a cue to reach for undocumented APIs.
 */
@Singleton
class AndroidAudioPort @Inject constructor(
    private val audioManager: AudioManager,
) : AudioPort {

    override fun currentRingerMode(): RingerMode =
        when (audioManager.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> RingerMode.SILENT
            AudioManager.RINGER_MODE_VIBRATE -> RingerMode.VIBRATE
            AudioManager.RINGER_MODE_NORMAL -> RingerMode.NORMAL
            else -> RingerMode.UNKNOWN
        }

    override fun currentRingVolume(): VolumeSnapshot = try {
        VolumeSnapshot(
            current = audioManager.getStreamVolume(AudioManager.STREAM_RING),
            max = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING),
        )
    } catch (e: Exception) {
        Timber.w(e, "Could not read ring volume")
        VolumeSnapshot.UNKNOWN
    }

    /**
     * Architecture.md § 7.1 — checked *before* attempting any volume change so
     * the audit entry can say "this device cannot do it" rather than emitting a
     * misleading generic failure.
     */
    override fun isVolumeFixed(): Boolean = try {
        audioManager.isVolumeFixed
    } catch (e: Exception) {
        Timber.w(e, "isVolumeFixed() unavailable; assuming adjustable")
        false
    }

    override fun setRingerMode(mode: RingerMode): Outcome<RingerMode> {
        val target = mode.toPlatform()
            ?: return Outcome.Failure(
                reason = FailureReason.UNKNOWN,
                detail = "Cannot set ringer to $mode",
            )

        val before = currentRingerMode()
        if (before == mode) return Outcome.Success(mode)

        try {
            audioManager.ringerMode = target
        } catch (e: SecurityException) {
            // Thrown when the transition would cross a DND boundary without
            // ACCESS_NOTIFICATION_POLICY. Expected and recoverable — the user
            // can grant it; the Permissions screen says so.
            Timber.w(e, "SecurityException setting ringer mode to %s", mode)
            return Outcome.Failure(
                reason = FailureReason.NOTIFICATION_POLICY_ACCESS_DENIED,
                detail = "setRingerMode($mode) denied: ${e.message}",
                cause = e,
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to set ringer mode to %s", mode)
            return Outcome.Failure(FailureReason.UNKNOWN, e.message, e)
        }

        // The read-back. This is the whole point of the class.
        val after = currentRingerMode()
        if (after == mode) return Outcome.Success(after)

        // Silent is its own honest failure. Architecture.md § 7.3 and the
        // project's standing constraint: never claim silent is always
        // overridable.
        val reason = if (before == RingerMode.SILENT) {
            FailureReason.SILENT_NOT_OVERRIDDEN
        } else {
            FailureReason.VERIFICATION_FAILED
        }

        Timber.w("Ringer mode did not change: asked %s, still %s", mode, after)
        return Outcome.Failure(
            reason = reason,
            detail = "Requested $mode, device reports $after",
        )
    }

    override fun setRingVolumePercent(percent: Int): Outcome<VolumeSnapshot> {
        val max = try {
            audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
        } catch (e: Exception) {
            return Outcome.Failure(FailureReason.UNKNOWN, "No max volume", e)
        }
        if (max <= 0) {
            return Outcome.Failure(FailureReason.VOLUME_FIXED, "Max ring volume is 0")
        }

        // Round up so a requested percentage never silently becomes a quieter
        // index — "80%" must not land on 3/5 (60%).
        val index = ((percent.coerceIn(0, 100) * max) + 99) / 100
        return setRingVolumeRaw(index.coerceIn(1, max))
    }

    override fun setRingVolumeRaw(index: Int): Outcome<VolumeSnapshot> {
        if (isVolumeFixed()) {
            return Outcome.Failure(
                reason = FailureReason.VOLUME_FIXED,
                detail = "AudioManager.isVolumeFixed() == true",
            )
        }

        try {
            // Flag 0 deliberately: no UI. Architecture.md § 7.4 — FLAG_SHOW_UI
            // would pop the volume panel over the incoming-call screen.
            audioManager.setStreamVolume(AudioManager.STREAM_RING, index, 0)
        } catch (e: SecurityException) {
            Timber.w(e, "SecurityException setting ring volume to %d", index)
            return Outcome.Failure(
                reason = FailureReason.NOTIFICATION_POLICY_ACCESS_DENIED,
                detail = "setStreamVolume($index) denied while DND active: ${e.message}",
                cause = e,
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to set ring volume to %d", index)
            return Outcome.Failure(FailureReason.UNKNOWN, e.message, e)
        }

        val after = currentRingVolume()
        return if (after.current == index) {
            Outcome.Success(after)
        } else {
            Timber.w("Ring volume did not change: asked %d, got %d", index, after.current)
            Outcome.Failure(
                reason = FailureReason.VERIFICATION_FAILED,
                detail = "Requested index $index, device reports ${after.current}",
            )
        }
    }

    private fun RingerMode.toPlatform(): Int? = when (this) {
        RingerMode.SILENT -> AudioManager.RINGER_MODE_SILENT
        RingerMode.VIBRATE -> AudioManager.RINGER_MODE_VIBRATE
        RingerMode.NORMAL -> AudioManager.RINGER_MODE_NORMAL
        RingerMode.UNKNOWN -> null
    }
}
