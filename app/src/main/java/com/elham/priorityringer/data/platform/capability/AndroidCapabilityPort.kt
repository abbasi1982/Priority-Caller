package com.elham.priorityringer.data.platform.capability

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.elham.priorityringer.domain.model.Capability
import com.elham.priorityringer.domain.model.CapabilityReport
import com.elham.priorityringer.domain.model.CapabilityStatus
import com.elham.priorityringer.domain.port.AudioPort
import com.elham.priorityringer.domain.port.CapabilityPort
import com.elham.priorityringer.domain.port.DndPort
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Builds the live [CapabilityReport] (FR6, FR8).
 *
 * Probed fresh on every call and never cached — these grants can be revoked
 * from Settings while the app is running, and a stale ✅ on a screen whose whole
 * purpose is telling the user whether the app works would be worse than no
 * screen at all.
 *
 * Every status carries a [CapabilityStatus.consequence] written in plain
 * language. Architecture.md § 12 requires it, and the domain type makes it
 * impossible to omit. The phrasing is deliberately specific about what stops
 * working, because the person reading it is usually setting the app up on
 * someone else's phone and cannot test by calling themselves.
 */
@Singleton
class AndroidCapabilityPort @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notificationManager: NotificationManager,
    private val audioPort: AudioPort,
    private val dndPort: DndPort,
) : CapabilityPort {

    override fun report(): CapabilityReport {
        val ringerMode = audioPort.currentRingerMode()
        val filter = dndPort.currentFilter()
        val volumeFixed = audioPort.isVolumeFixed()

        return CapabilityReport(
            statuses = listOf(
                readPhoneState(),
                readCallLog(),
                readContacts(),
                notificationPolicyAccess(),
                postNotifications(),
                fullScreenIntent(),
                volumeAdjustable(volumeFixed),
                batteryOptimisation(),
            ),
            sdkInt = Build.VERSION.SDK_INT,
            targetSdk = context.applicationInfo.targetSdkVersion,
            currentRingerMode = ringerMode,
            currentInterruptionFilter = filter,
            isVolumeFixed = volumeFixed,
            currentRingVolume = audioPort.currentRingVolume(),
            notes = buildNotes(volumeFixed),
        )
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun readPhoneState() = CapabilityStatus(
        capability = Capability.READ_PHONE_STATE,
        state = granted(Manifest.permission.READ_PHONE_STATE).toState(),
        consequence = "Without this, the app is never told that a call is " +
            "arriving, so it can do nothing at all.",
    )

    /**
     * Architecture.md § A.5 — the consequence text says *inert*, not
     * "reduced". Since Android 9 the `PHONE_STATE` broadcast omits the caller
     * number unless this is granted, so without it no caller can ever be
     * identified and priority ringing can never happen. Softening this wording
     * would leave someone believing the app half-works when it does nothing.
     */
    private fun readCallLog() = CapabilityStatus(
        capability = Capability.READ_CALL_LOG,
        state = granted(Manifest.permission.READ_CALL_LOG).toState(),
        consequence = "Without this, Android hides the caller's number from the " +
            "app. No caller can be recognised, so priority ringing will never " +
            "happen — the app does nothing at all until this is granted.",
    )

    private fun readContacts() = CapabilityStatus(
        capability = Capability.READ_CONTACTS,
        state = granted(Manifest.permission.READ_CONTACTS).toState(),
        consequence = "Optional. Without it you can still add numbers by hand or " +
            "with the contact picker; only browsing your full contact list is " +
            "unavailable.",
    )

    private fun notificationPolicyAccess() = CapabilityStatus(
        capability = Capability.NOTIFICATION_POLICY_ACCESS,
        state = runCatching { notificationManager.isNotificationPolicyAccessGranted }
            .getOrDefault(false)
            .toState(),
        consequence = "Without this, the app cannot change Do Not Disturb, and " +
            "cannot change the ringer or volume while Do Not Disturb is on. " +
            "Priority calls will still be detected but may stay silent.",
    )

    private fun postNotifications() = CapabilityStatus(
        capability = Capability.POST_NOTIFICATIONS,
        state = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            granted(Manifest.permission.POST_NOTIFICATIONS).toState()
        } else {
            CapabilityStatus.State.NOT_REQUIRED
        },
        consequence = "Without this, the repeat-caller alert cannot be shown. " +
            "Ringer and volume changes still work.",
    )

    private fun fullScreenIntent(): CapabilityStatus {
        val state = when {
            Build.VERSION.SDK_INT < 34 -> CapabilityStatus.State.GRANTED
            runCatching { notificationManager.canUseFullScreenIntent() }
                .getOrDefault(false) -> CapabilityStatus.State.GRANTED

            else -> CapabilityStatus.State.DENIED
        }
        return CapabilityStatus(
            capability = Capability.FULL_SCREEN_INTENT,
            state = state,
            consequence = "Without this, a repeat caller shows a normal " +
                "heads-up notification instead of taking over the screen. " +
                "Everything else still works.",
            detail = if (Build.VERSION.SDK_INT >= 34) {
                "Android 14+ requires you to allow full-screen notifications for this app."
            } else {
                null
            },
        )
    }

    /**
     * A device capability, not a permission — hence [CapabilityStatus.State.RESTRICTED]
     * rather than DENIED, so the UI offers an explanation and no useless button.
     */
    private fun volumeAdjustable(volumeFixed: Boolean) = CapabilityStatus(
        capability = Capability.VOLUME_ADJUSTABLE,
        state = if (volumeFixed) {
            CapabilityStatus.State.RESTRICTED
        } else {
            CapabilityStatus.State.GRANTED
        },
        consequence = "This device reports a fixed output volume, so ring volume " +
            "cannot be raised. The ringer mode can still be switched out of " +
            "vibrate.",
    )

    /**
     * Battery optimisation exemption.
     *
     * The consequence text is deliberately narrow. It would be easy — and
     * wrong — to say "without this the app may miss calls". `PHONE_STATE` is a
     * system broadcast and is delivered to a manifest receiver regardless of
     * the app's battery bucket. What optimisation actually delays is
     * *deferrable background work*, which is what the restore watchdog is.
     *
     * Overstating it here would train the user to grant everything on the
     * strength of a claim that is not true, which is the same dishonesty as
     * understating a real failure.
     *
     * `isIgnoringBatteryOptimizations` is guarded like every other probe in
     * this class: an OEM build that refuses the query must degrade to "cannot
     * tell", never crash the screen that exists to explain the app's state.
     */
    private fun batteryOptimisation(): CapabilityStatus {
        val exempt = runCatching {
            context.getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrElse { error ->
            Timber.w(error, "Could not read battery optimisation state")
            null
        }

        return CapabilityStatus(
            capability = Capability.BATTERY_OPTIMISATION_EXEMPT,
            state = when (exempt) {
                true -> CapabilityStatus.State.GRANTED
                false -> CapabilityStatus.State.DENIED
                // Unreadable: offering a button would be guessing, and showing
                // a red ❌ would be asserting something we do not know.
                null -> CapabilityStatus.State.RESTRICTED
            },
            consequence = "Calls are still detected either way — Android delivers " +
                "the call alert to this app regardless. What battery " +
                "optimisation can delay is the timer that puts your ringer and " +
                "Do Not Disturb back if a call ends unexpectedly, so the phone " +
                "could stay loud for longer than the auto-restore setting says.",
            detail = if (exempt == null) {
                "This device did not let the app check its battery optimisation state."
            } else {
                null
            },
        )
    }

    /** Documented limitations for this specific device and API level (§ 3). */
    private fun buildNotes(volumeFixed: Boolean): List<String> = buildList {
        add(
            "Calls are detected when ringing begins, not before it. Volume is " +
                "raised within the first moment of the ringtone rather than " +
                "ahead of it.",
        )
        if (context.applicationInfo.targetSdkVersion >= 35 && Build.VERSION.SDK_INT >= 35) {
            add(
                "On Android 15 and later, Do Not Disturb rules combine using the " +
                    "strictest setting. A Do Not Disturb mode you set yourself " +
                    "takes precedence and cannot be overridden by an app.",
            )
        }
        if (volumeFixed) {
            add("This device reports a fixed output volume; volume changes are not possible.")
        }
        add(
            "Silent mode is controlled by you and by the device manufacturer. " +
                "The app attempts to switch out of it and reports honestly " +
                "whether that worked — it cannot promise it will.",
        )
    }

    private fun Boolean.toState(): CapabilityStatus.State =
        if (this) CapabilityStatus.State.GRANTED else CapabilityStatus.State.DENIED
}
