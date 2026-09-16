package com.elham.priorityringer.data.platform.audio

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.elham.priorityringer.domain.model.RingerMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The platform claim the Silent-restore fix rests on, checked on a device.
 *
 * A phone left on **Silent** came back from a call on **Vibrate**. The cause was
 * that on Android the ring-stream index is the silent/vibrate control, not an
 * independent setting: `AudioService.onSetStreamVolume` turns a write of 0 into
 * a ringer-mode change. Restore set SILENT and then wrote the snapshot's index
 * of 0, and that write moved the mode.
 *
 * The fix assumes two things about real devices, and assumptions about audio
 * behaviour are exactly what this project is not willing to leave unchecked:
 *
 *  1. `setRingerMode` **alone** is enough to reach SILENT and VIBRATE. If that
 *     were false, skipping the volume write would leave the phone in the wrong
 *     mode — a worse bug than the one being fixed.
 *  2. A volume write is not needed to complete a silent restore.
 *
 * Both are asserted below. The coupling itself is *observed and logged* rather
 * than asserted: a device that leaves the mode alone when 0 is written makes the
 * guard unnecessary, not wrong, and failing there would be a test punishing a
 * device for being better behaved than the one that found the bug.
 *
 * **Restores whatever the device was in.** An instrumented test that leaves a
 * phone on Silent is the same class of harm the app itself is built to avoid.
 */
@RunWith(AndroidJUnit4::class)
class RingerModeAndVolumeCouplingTest {

    private lateinit var audioManager: AudioManager
    private lateinit var port: AndroidAudioPort

    private var originalMode: Int = AudioManager.RINGER_MODE_NORMAL
    private var originalVolume: Int = 0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        audioManager = context.getSystemService(AudioManager::class.java)
        port = AndroidAudioPort(audioManager)

        originalMode = audioManager.ringerMode
        originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_RING)

        // Moving into or out of silent crosses a Do Not Disturb boundary, which
        // the platform gates on notification-policy access. Without it every
        // assertion here would fail for a reason that has nothing to do with
        // the behaviour under test, so skip loudly instead.
        //
        // Grant it with:
        //   adb shell cmd notification allow_dnd com.elham.priorityringer
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        assumeTrue(
            "needs Do Not Disturb access: " +
                "adb shell cmd notification allow_dnd com.elham.priorityringer",
            notificationManager.isNotificationPolicyAccessGranted,
        )
    }

    @After
    fun tearDown() {
        runCatching {
            audioManager.ringerMode = originalMode
            if (originalMode == AudioManager.RINGER_MODE_NORMAL && originalVolume > 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_RING, originalVolume, 0)
            }
        }.onFailure { Log.w(TAG, "Could not put the ringer back", it) }
    }

    @Test
    fun setting_the_mode_alone_is_enough_to_reach_SILENT() {
        givenAnAudiblePhone()

        val outcome = port.setRingerMode(RingerMode.SILENT)

        assertTrue("setRingerMode(SILENT) reported $outcome", outcome.isSuccess)
        assertEquals(
            "restore skips the volume write in silent modes, so the mode call " +
                "has to carry it by itself",
            RingerMode.SILENT,
            port.currentRingerMode(),
        )
    }

    @Test
    fun setting_the_mode_alone_is_enough_to_reach_VIBRATE() {
        givenAnAudiblePhone()

        port.setRingerMode(RingerMode.VIBRATE)

        assertEquals(RingerMode.VIBRATE, port.currentRingerMode())
    }

    @Test
    fun the_restore_sequence_for_a_silent_phone_ends_silent() {
        // Exactly what RestoreAudioAndDndUseCase does for a SILENT snapshot:
        // write the remembered audible level while the phone is still audible,
        // then silence it — and never write the snapshot's index of 0.
        givenAnAudiblePhone()
        val rememberedAudibleIndex = 2

        port.setRingVolumeRaw(rememberedAudibleIndex)
        port.setRingerMode(RingerMode.SILENT)

        assertEquals(
            "this is the exact sequence that must leave a Silent phone Silent",
            RingerMode.SILENT,
            port.currentRingerMode(),
        )
    }

    @Test
    fun the_audible_level_written_before_silencing_survives_the_return_to_normal() {
        givenAnAudiblePhone()
        val rememberedAudibleIndex = 2

        port.setRingVolumeRaw(rememberedAudibleIndex)
        port.setRingerMode(RingerMode.SILENT)
        port.setRingerMode(RingerMode.NORMAL)

        assertEquals(
            "the point of remembering the level: switching the ringer back on " +
                "must give the user their own volume, not the app's",
            rememberedAudibleIndex,
            port.currentRingVolume().current,
        )
    }

    /**
     * Observation, not assertion. Writing 0 is what moved a real phone from
     * Silent to Vibrate; a device that does not do it makes the guard
     * unnecessary rather than wrong, so this records what happened and does not
     * fail either way.
     */
    @Test
    fun writing_a_zero_ring_index_is_recorded_for_whatever_it_does_here() {
        givenAnAudiblePhone()
        port.setRingerMode(RingerMode.SILENT)

        port.setRingVolumeRaw(0)
        val after = port.currentRingerMode()

        // android.util.Log, not Timber: under instrumentation the app runs on
        // HiltTestApplication, so PriorityRingerApp.onCreate never runs and no
        // Timber tree is ever planted. A Timber call here would be silently
        // dropped — and a test whose entire purpose is to record an observation
        // that records nothing is worse than no test.
        Log.i(
            TAG,
            "Ring index 0 written while SILENT left the device in $after " +
                "(vibrate-when-ringing decides which). This is why restore never writes it.",
        )
    }

    private companion object {
        const val TAG = "RingerCouplingTest"
    }

    private fun givenAnAudiblePhone() {
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
        audioManager.setStreamVolume(AudioManager.STREAM_RING, (max / 2).coerceAtLeast(1), 0)
        assumeTrue(
            "this device cannot be put into NORMAL; nothing below is meaningful",
            audioManager.ringerMode == AudioManager.RINGER_MODE_NORMAL,
        )
    }
}
