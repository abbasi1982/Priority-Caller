package com.elham.priorityringer.data.platform.audio

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.elham.priorityringer.domain.port.RingtonePlayerPort
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Does the backup alert actually make a sound on this device?
 *
 * Every other test of this feature runs against a fake. The fake cannot tell us
 * that the ringtone URI resolves on this OEM's build, that `MediaPlayer`
 * prepares it, or — the part that matters — that the audio genuinely lands on
 * `STREAM_ALARM` while the phone is in Silent. That is the whole claim of the
 * fallback, and until this test ran it had never been checked anywhere.
 *
 * **Playback is verified from outside the port**, via
 * `AudioManager.getActivePlaybackConfigurations()`. Asking the port whether it
 * is playing would just be asking the code under test to vouch for itself, and
 * `MediaPlayer.isPlaying` is a transport flag that reports true even for audio
 * nothing can hear. The system's own list of active players, filtered to
 * `USAGE_ALARM`, is independent evidence that this process is producing sound on
 * the alarm stream.
 *
 * **What this test does not do: answer P2.** P2 asks whether playback survives
 * after a `PHONE_STATE` broadcast's `goAsync()` window closes, with the process
 * frozen as a cached app. That needs a real incoming call on a device with a
 * SIM. Nothing here runs from a broadcast, and the app is in the foreground
 * throughout.
 *
 * **It makes a noise.** Briefly, at the lowest alarm volume the device allows,
 * and it stops the sound and restores ringer mode, interruption filter and alarm
 * volume in `@After`.
 */
@RunWith(AndroidJUnit4::class)
class AndroidRingtonePlayerPortTest {

    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var port: AndroidRingtonePlayerPort

    private var originalMode: Int = AudioManager.RINGER_MODE_NORMAL
    private var originalAlarmVolume: Int = 0
    private var originalFilter: Int = NotificationManager.INTERRUPTION_FILTER_ALL

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        audioManager = context.getSystemService(AudioManager::class.java)
        notificationManager = context.getSystemService(NotificationManager::class.java)
        port = AndroidRingtonePlayerPort(context, audioManager, notificationManager)

        originalMode = audioManager.ringerMode
        originalAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        originalFilter = notificationManager.currentInterruptionFilter

        assumeTrue(
            "needs Do Not Disturb access: " +
                "adb shell cmd notification allow_dnd com.elham.priorityringer",
            notificationManager.isNotificationPolicyAccessGranted,
        )
    }

    @After
    fun tearDown() {
        runCatching { port.stopAlarmStreamAlert() }
            .onFailure { Log.w(TAG, "Could not stop the alert", it) }
        runCatching { notificationManager.setInterruptionFilter(originalFilter) }
            .onFailure { Log.w(TAG, "Could not put the filter back", it) }
        runCatching {
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, originalAlarmVolume, 0)
            audioManager.ringerMode = originalMode
        }.onFailure { Log.w(TAG, "Could not put the audio state back", it) }
    }

    /**
     * The claim the whole fallback rests on: in Silent, this app can still be
     * heard, because the sound it makes is not the ringer.
     */
    @Test
    fun the_alert_really_sounds_on_the_alarm_stream_while_the_phone_is_SILENT() {
        givenAQuietButAudibleAlarmStreamInSilent()

        val outcome = port.startAlarmStreamAlert()

        assertTrue("startAlarmStreamAlert reported $outcome", outcome.isSuccess)
        assertTrue(
            "the system reports no active USAGE_ALARM player from this app, so " +
                "nothing is actually coming out of the speaker. Active usages: " +
                activeUsages(),
            awaitAlarmPlayback(expected = true),
        )
        assertEquals(
            "the device must still be in Silent — the point is that the alert " +
                "does not need the ringer, not that it quietly turns it back on",
            AudioManager.RINGER_MODE_SILENT,
            audioManager.ringerMode,
        )
    }

    @Test
    fun stopping_the_alert_really_stops_the_sound() {
        givenAQuietButAudibleAlarmStreamInSilent()
        port.startAlarmStreamAlert()
        assumeTrue("playback never started; nothing to stop", awaitAlarmPlayback(expected = true))

        port.stopAlarmStreamAlert()

        assertTrue(
            "a phone that will not go quiet is the worst failure this port has. " +
                "Active usages: " + activeUsages(),
            awaitAlarmPlayback(expected = false),
        )
    }

    /** Called on the main thread the instant the user answers; must never blow up. */
    @Test
    fun stopping_twice_is_harmless() {
        givenAQuietButAudibleAlarmStreamInSilent()
        port.startAlarmStreamAlert()

        port.stopAlarmStreamAlert()
        port.stopAlarmStreamAlert()

        assertTrue(awaitAlarmPlayback(expected = false))
    }

    /**
     * A second priority call mid-alert must replace the sound, not layer a
     * second ringtone over the first.
     */
    @Test
    fun starting_twice_leaves_exactly_one_player() {
        givenAQuietButAudibleAlarmStreamInSilent()

        port.startAlarmStreamAlert()
        port.startAlarmStreamAlert()
        awaitAlarmPlayback(expected = true)

        assertEquals(
            "two overlapping ringtones is a bug the user would hear immediately",
            1,
            alarmPlayerCount(),
        )
    }

    /**
     * The ordering that a real device made load-bearing.
     *
     * On the phone this was first run on, Total Silence does **not** set
     * `isStreamMute(STREAM_ALARM)` — it drives the alarm volume to 0 — and the
     * notification policy still reports alarms as an allowed category. So both
     * obvious single checks report "audible" here, and only checking
     * `INTERRUPTION_FILTER_NONE` first gets it right.
     */
    @Test
    fun total_silence_is_predicted_as_MUTED_BY_DND() {
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        assumeTrue(
            "this device would not enter Total Silence",
            awaitFilter(NotificationManager.INTERRUPTION_FILTER_NONE),
        )

        assertEquals(
            RingtonePlayerPort.AlarmAudibility.MUTED_BY_DND,
            port.alarmAudibility(),
        )
    }

    private fun givenAQuietButAudibleAlarmStreamInSilent() {
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        awaitFilter(NotificationManager.INTERRUPTION_FILTER_ALL)

        // The minimum the device will accept rather than a comfortable level:
        // this test is run by a person sitting next to the phone, and it only
        // needs the stream to be non-zero to be meaningful.
        val min = runCatching { audioManager.getStreamMinVolume(AudioManager.STREAM_ALARM) }
            .getOrDefault(1)
            .coerceAtLeast(1)
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, min, 0)

        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
        assumeTrue(
            "this device would not enter Silent; nothing below is meaningful",
            audioManager.ringerMode == AudioManager.RINGER_MODE_SILENT,
        )
        assumeTrue(
            "the alarm stream is muted or at zero here, so no sound could be " +
                "expected regardless of the port",
            !audioManager.isStreamMute(AudioManager.STREAM_ALARM) &&
                audioManager.getStreamVolume(AudioManager.STREAM_ALARM) > 0,
        )
    }

    private fun alarmPlayerCount(): Int = runCatching {
        audioManager.activePlaybackConfigurations.count {
            it.audioAttributes.usage == AudioAttributes.USAGE_ALARM
        }
    }.getOrDefault(0)

    private fun activeUsages(): String = runCatching {
        audioManager.activePlaybackConfigurations
            .joinToString { it.audioAttributes.usage.toString() }
            .ifEmpty { "(none)" }
    }.getOrDefault("(unreadable)")

    /**
     * `MediaPlayer.start()` returns before the system's playback registry
     * catches up, and release is equally asynchronous. Polling beats a fixed
     * sleep: quick on a fast device, still correct on a slow one.
     */
    private fun awaitAlarmPlayback(expected: Boolean): Boolean {
        repeat(POLL_ATTEMPTS) {
            if ((alarmPlayerCount() > 0) == expected) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return false
    }

    private fun awaitFilter(expected: Int): Boolean {
        repeat(POLL_ATTEMPTS) {
            if (notificationManager.currentInterruptionFilter == expected) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return false
    }

    private companion object {
        const val TAG = "RingtonePlayerTest"
        const val POLL_ATTEMPTS = 25
        const val POLL_INTERVAL_MS = 100L
    }
}
