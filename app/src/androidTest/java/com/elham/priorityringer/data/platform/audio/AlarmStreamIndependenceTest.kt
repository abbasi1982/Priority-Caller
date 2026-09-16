package com.elham.priorityringer.data.platform.audio

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The platform claim the alarm-stream fallback rests on, checked on a device.
 *
 * When the ringer approach is verified to have failed — Silent could not be
 * overridden, or a stricter Do Not Disturb won under most-restrictive-wins — the
 * app plays the user's ringtone on the **alarm stream** instead. That only helps
 * if two things are true of real devices:
 *
 *  1. `STREAM_ALARM` is **not** silenced by ringer mode. If it were, the
 *     fallback would be inaudible in exactly the case it exists for.
 *  2. Writing an alarm index does **not** move the ringer mode. The *ring*
 *     stream has precisely that coupling — writing index 0 there is what took a
 *     real phone from Silent to Vibrate, and it is why
 *     [RingerModeAndVolumeCouplingTest] exists. There is no reason yet to
 *     believe the alarm stream has no sibling behaviour, and assuming it does
 *     not is the same assumption class that produced the last regression.
 *
 * Both are asserted. Do Not Disturb is *observed and logged* instead: the
 * official reference says of `ZenPolicy.getPriorityCategoryAlarms()` that "when
 * alarms are disallowed, the alarm stream will be muted when DND is active", so
 * the alarm stream is a **conditional** bypass whose behaviour depends on a
 * policy the user owns. Asserting a particular outcome would be asserting the
 * test device's DND settings. What the log gives instead is the ground truth to
 * check `AndroidRingtonePlayerPort.alarmAudibility()` against.
 *
 * **Restores whatever the device was in** — filter, ringer mode and alarm
 * volume. A test that leaves a phone silent, or its alarms muted, is the same
 * class of harm the app itself is built to avoid.
 */
@RunWith(AndroidJUnit4::class)
class AlarmStreamIndependenceTest {

    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager

    private var originalMode: Int = AudioManager.RINGER_MODE_NORMAL
    private var originalAlarmVolume: Int = 0
    private var originalFilter: Int = NotificationManager.INTERRUPTION_FILTER_ALL

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        audioManager = context.getSystemService(AudioManager::class.java)
        notificationManager = context.getSystemService(NotificationManager::class.java)

        originalMode = audioManager.ringerMode
        originalAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        originalFilter = notificationManager.currentInterruptionFilter

        // Moving into or out of silent crosses a Do Not Disturb boundary, and
        // changing the interruption filter obviously does. Without policy
        // access every assertion here would fail for a reason that has nothing
        // to do with the behaviour under test, so skip loudly instead.
        //
        // Grant it with:
        //   adb shell cmd notification allow_dnd com.elham.priorityringer
        assumeTrue(
            "needs Do Not Disturb access: " +
                "adb shell cmd notification allow_dnd com.elham.priorityringer",
            notificationManager.isNotificationPolicyAccessGranted,
        )
    }

    @After
    fun tearDown() {
        // Filter first: while a restrictive filter is active the platform may
        // refuse the volume and ringer writes below, and leaving a phone with
        // its alarms muted is worse than leaving it silent.
        runCatching { notificationManager.setInterruptionFilter(originalFilter) }
            .onFailure { Log.w(TAG, "Could not put the interruption filter back", it) }
        runCatching {
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, originalAlarmVolume, 0)
            audioManager.ringerMode = originalMode
        }.onFailure { Log.w(TAG, "Could not put the ringer back", it) }
    }

    @Test
    fun the_alarm_stream_is_not_silenced_by_SILENT() {
        assertAlarmStreamSurvives(AudioManager.RINGER_MODE_SILENT)
    }

    @Test
    fun the_alarm_stream_is_not_silenced_by_VIBRATE() {
        assertAlarmStreamSurvives(AudioManager.RINGER_MODE_VIBRATE)
    }

    /**
     * The sibling-coupling check.
     *
     * On the ring stream, index and ringer mode are one setting. If the same
     * were true of the alarm stream, then merely reading or restoring an alarm
     * level of 0 could silence the phone — a far worse bug than the one the
     * fallback fixes, and invisible to every JVM test.
     */
    @Test
    fun writing_a_zero_alarm_index_does_not_move_the_ringer_mode() {
        givenAnAudiblePhone()

        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, 0, 0)

        assertEquals(
            "writing alarm index 0 changed the ringer mode; the alarm stream " +
                "has the same coupling the ring stream does and the fallback " +
                "design must account for it",
            AudioManager.RINGER_MODE_NORMAL,
            audioManager.ringerMode,
        )
    }

    /**
     * Observation, not assertion — the ground truth for `alarmAudibility()`.
     *
     * Whether DND mutes the alarm stream depends on the user's own policy, so
     * failing here would be a test punishing a device for a setting it is
     * entitled to have. What this records is which filters actually mute the
     * stream on this device, and what the policy said at the time, so the app's
     * prediction can be checked against it.
     */
    @Test
    fun alarm_stream_muting_under_each_interruption_filter_is_recorded() {
        givenAnAudiblePhone()

        listOf(
            NotificationManager.INTERRUPTION_FILTER_ALL to "ALL",
            NotificationManager.INTERRUPTION_FILTER_PRIORITY to "PRIORITY",
            NotificationManager.INTERRUPTION_FILTER_ALARMS to "ALARMS",
            NotificationManager.INTERRUPTION_FILTER_NONE to "NONE",
        ).forEach { (filter, name) ->
            val applied = runCatching {
                notificationManager.setInterruptionFilter(filter)
                awaitFilter(filter)
            }.getOrDefault(false)

            // android.util.Log, not Timber: under instrumentation the app runs
            // on HiltTestApplication, so PriorityRingerApp.onCreate never runs
            // and no Timber tree is ever planted. A Timber call here would be
            // silently dropped — and an observation test that records nothing
            // is worse than no test.
            Log.i(
                TAG,
                "filter=$name applied=$applied " +
                    "alarmMuted=${audioManager.isStreamMute(AudioManager.STREAM_ALARM)} " +
                    "alarmVolume=${audioManager.getStreamVolume(AudioManager.STREAM_ALARM)} " +
                    "allowsAlarms=${policyAllowsAlarms()}",
            )
        }
    }

    private fun assertAlarmStreamSurvives(mode: Int) {
        givenAnAudiblePhone()
        val before = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)

        audioManager.ringerMode = mode
        assumeTrue(
            "this device would not enter mode $mode; nothing below is meaningful",
            audioManager.ringerMode == mode,
        )

        assertFalse(
            "the alarm stream was muted by ringer mode $mode — the whole " +
                "fallback is void on this device",
            audioManager.isStreamMute(AudioManager.STREAM_ALARM),
        )
        assertEquals(
            "ringer mode $mode changed the alarm volume",
            before,
            audioManager.getStreamVolume(AudioManager.STREAM_ALARM),
        )
    }

    private fun policyAllowsAlarms(): Boolean = runCatching {
        val categories = notificationManager.notificationPolicy.priorityCategories
        categories and NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS != 0
    }.getOrDefault(false)

    /**
     * The filter is applied asynchronously and the stream mute follows it, so a
     * read taken immediately after the write can catch the old value. Polling
     * beats a fixed sleep: it is faster when the device is quick and still
     * correct when it is not.
     */
    private fun awaitFilter(expected: Int): Boolean {
        repeat(FILTER_POLL_ATTEMPTS) {
            if (notificationManager.currentInterruptionFilter == expected) return true
            Thread.sleep(FILTER_POLL_INTERVAL_MS)
        }
        return false
    }

    private fun givenAnAudiblePhone() {
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        awaitFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL

        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, (max / 2).coerceAtLeast(1), 0)

        assumeTrue(
            "this device cannot be put into NORMAL; nothing below is meaningful",
            audioManager.ringerMode == AudioManager.RINGER_MODE_NORMAL,
        )
    }

    private companion object {
        const val TAG = "AlarmStreamTest"
        const val FILTER_POLL_ATTEMPTS = 20
        const val FILTER_POLL_INTERVAL_MS = 100L
    }
}
