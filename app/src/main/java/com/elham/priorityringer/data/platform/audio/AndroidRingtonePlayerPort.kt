package com.elham.priorityringer.data.platform.audio

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.elham.priorityringer.domain.model.FailureReason
import com.elham.priorityringer.domain.model.Outcome
import com.elham.priorityringer.domain.port.RingtonePlayerPort
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * `MediaPlayer` implementation of [RingtonePlayerPort].
 *
 * ## What it plays
 *
 * The user's **own** default ringtone, not a sound bundled with this app. Only
 * the route changes: `USAGE_ALARM` instead of the ring stream. A person whose
 * phone rings loudly in Silent should still recognise the sound as their phone.
 *
 * ## Why there is a timer in here
 *
 * Three separate paths stop this alert — the `PHONE_STATE` receiver on
 * OFFHOOK/IDLE, the restore use case, and the watchdog. The timer is the fourth,
 * and exists because the others can all be lost at once: if the process is
 * frozen or the broadcast never arrives, nothing else in the app would ever
 * silence it. An alert that cannot stop is worse than one that never starts, and
 * this is the same reasoning that gives restore its own watchdog (§ A.3).
 *
 * ## No audio focus request
 *
 * Deliberate, and recorded rather than forgotten. Requesting focus would pause
 * whatever the user is listening to, which is the correct behaviour for a real
 * ringtone but is a side effect this app would then have to undo — and undoing
 * side effects is exactly where its bugs have lived. `USAGE_ALARM` ducks other
 * audio on its own. If the device matrix shows the alert being drowned out,
 * that is a second pass with its own test.
 */
@Singleton
class AndroidRingtonePlayerPort @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioManager: AudioManager,
    private val notificationManager: NotificationManager,
) : RingtonePlayerPort {

    private val lock = Any()
    private val guard = Handler(Looper.getMainLooper())

    /** Non-null exactly while an alert is playing. Guarded by [lock]. */
    private var player: MediaPlayer? = null

    /**
     * Bumped by every start and every stop, so a start that was superseded
     * while it was preparing can tell, and throw away its player instead of
     * registering it behind the stop's back. Guarded by [lock].
     */
    private var generation: Int = 0

    private val stopOnTimeout = Runnable {
        Timber.w("Alarm-stream alert hit its %d ms guard; stopping it", MAX_DURATION_MS)
        stopAlarmStreamAlert()
    }

    override fun alarmAudibility(): RingtonePlayerPort.AlarmAudibility {
        val hasAccess = runCatching { notificationManager.isNotificationPolicyAccessGranted }
            .getOrDefault(false)
        if (!hasAccess) return RingtonePlayerPort.AlarmAudibility.UNKNOWN

        val filter = runCatching { notificationManager.currentInterruptionFilter }
            .getOrDefault(NotificationManager.INTERRUPTION_FILTER_UNKNOWN)

        return when (filter) {
            // Total Silence stops alarms too. This is the honest gap in the
            // whole approach, and the one case the app must not claim to solve.
            NotificationManager.INTERRUPTION_FILTER_NONE ->
                RingtonePlayerPort.AlarmAudibility.MUTED_BY_DND

            NotificationManager.INTERRUPTION_FILTER_PRIORITY ->
                if (policyAllowsAlarms()) volumeAudibility() else {
                    RingtonePlayerPort.AlarmAudibility.MUTED_BY_DND
                }

            NotificationManager.INTERRUPTION_FILTER_ALL,
            NotificationManager.INTERRUPTION_FILTER_ALARMS,
            -> volumeAudibility()

            else -> RingtonePlayerPort.AlarmAudibility.UNKNOWN
        }
    }

    override fun startAlarmStreamAlert(): Outcome<RingtonePlayerPort.AlertDelivery> {
        // The lock is held only around the shared field, never across
        // `prepare()`.
        //
        // `stopAlarmStreamAlert` is called synchronously from `onReceive` — on
        // the main thread — the moment the user answers. If this method held
        // the lock through a media decode, that stop would block the main
        // thread waiting for it. The whole reason the stop is on that call path
        // is that it must not wait behind anything.
        val thisAttempt = synchronized(lock) {
            // Never overlap. A second priority call arriving mid-alert should
            // replace the sound, not layer a second copy on top of it.
            releaseLocked()
            ++generation
        }

        val uri = alertSoundUri()
            ?: return Outcome.Failure(
                reason = FailureReason.UNKNOWN,
                detail = "No ringtone or alarm sound is configured on this device",
            )

        val created = try {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(context, uri)
                isLooping = true
                setOnErrorListener { _, what, extra ->
                    Timber.e("Alarm-stream alert failed: what=%d extra=%d", what, extra)
                    stopAlarmStreamAlert()
                    true
                }
                // prepare(), not prepareAsync(): a ringtone is local content,
                // and the caller needs a straight answer about whether playback
                // began before it writes the audit entry that tells the user so.
                prepare()
                start()
            }
        } catch (e: SecurityException) {
            Timber.w(e, "Not allowed to play the ringtone at %s", uri)
            return Outcome.Failure(
                reason = FailureReason.SECURITY_EXCEPTION,
                detail = "Cannot read the configured ringtone: ${e.message}",
                cause = e,
            )
        } catch (e: Exception) {
            Timber.e(e, "Could not start the alarm-stream alert")
            return Outcome.Failure(FailureReason.UNKNOWN, e.message, e)
        }

        // A transport check, and **not** the read-back that § 6.2 means.
        //
        // `isPlaying` is true as soon as the player reaches Started state. It
        // says nothing about whether sound reaches the speaker: a muted alarm
        // stream, a zero volume or a refused route all report true. So this
        // catches a player that failed to start and nothing more.
        //
        // Whether the alert can actually be *heard* is not verifiable after the
        // fact at all — which is exactly why [alarmAudibility] is read
        // beforehand and its answer written to the audit log. Claiming this
        // line verifies audibility would be the kind of unearned success report
        // § 6.2 exists to forbid.
        if (!created.isPlaying) {
            runCatching { created.release() }
            Timber.w("MediaPlayer.start() returned but isPlaying is false")
            return Outcome.Failure(
                reason = FailureReason.VERIFICATION_FAILED,
                detail = "Playback did not start",
            )
        }

        synchronized(lock) {
            // Someone stopped us, or started a newer alert, while this one was
            // preparing. Registering the player now would make it invisible to
            // the stop that already ran — a phone that will not go quiet, which
            // is the worst outcome this port has.
            if (generation != thisAttempt) {
                runCatching { created.release() }
                Timber.i("Alarm-stream alert was superseded before it was registered")
                return Outcome.Failure(
                    reason = FailureReason.NOTHING_TO_DO,
                    detail = "Superseded before it started",
                )
            }

            releaseLocked()
            player = created
            guard.removeCallbacks(stopOnTimeout)
            guard.postDelayed(stopOnTimeout, MAX_DURATION_MS)
        }

        Timber.i("Alarm-stream alert started (alarm volume %d/%d)", alarmVolume(), alarmMax())

        // A foreground service cannot be started from a PHONE_STATE broadcast
        // without the battery-optimisation exemption, and whether one is needed
        // at all is still an open measurement on the target device (plan step
        // P2). Until that is answered, playback is held up by nothing, which is
        // what IN_PROCESS says.
        return Outcome.Success(RingtonePlayerPort.AlertDelivery.IN_PROCESS)
    }

    override fun stopAlarmStreamAlert() {
        synchronized(lock) {
            generation++
            guard.removeCallbacks(stopOnTimeout)
            releaseLocked()
        }
    }

    /** Caller must hold [lock]. Safe with nothing playing. */
    private fun releaseLocked() {
        val current = player ?: return
        player = null
        runCatching {
            if (current.isPlaying) current.stop()
            current.release()
        }.onFailure { Timber.w(it, "Could not release the alarm-stream player") }
    }

    /**
     * The user's ringtone first, because recognising the sound is half of what
     * makes an alert useful. A phone whose ringtone is set to "None" has no
     * `TYPE_RINGTONE` uri at all, so the default alarm sound is the fallback —
     * silence would defeat the point.
     */
    private fun alertSoundUri(): Uri? = runCatching {
        RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            ?: Settings.System.DEFAULT_ALARM_ALERT_URI
    }.onFailure { Timber.w(it, "Could not resolve a ringtone uri") }.getOrNull()

    private fun policyAllowsAlarms(): Boolean = runCatching {
        val categories = notificationManager.notificationPolicy.priorityCategories
        categories and NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS != 0
    }.getOrDefault(false)

    private fun volumeAudibility(): RingtonePlayerPort.AlarmAudibility {
        val muted = runCatching { audioManager.isStreamMute(AudioManager.STREAM_ALARM) }
            .getOrDefault(false)
        return if (muted || alarmVolume() <= 0) {
            RingtonePlayerPort.AlarmAudibility.VOLUME_ZERO
        } else {
            RingtonePlayerPort.AlarmAudibility.AUDIBLE
        }
    }

    private fun alarmVolume(): Int =
        runCatching { audioManager.getStreamVolume(AudioManager.STREAM_ALARM) }.getOrDefault(0)

    private fun alarmMax(): Int =
        runCatching { audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM) }.getOrDefault(0)

    private companion object {
        /**
         * Longer than a phone rings before going to voicemail (~30s), short
         * enough that a stuck alert is an annoyance rather than an emergency.
         */
        const val MAX_DURATION_MS = 60_000L
    }
}
