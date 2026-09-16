package com.elham.priorityringer.data.platform.alert

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.elham.priorityringer.R
import com.elham.priorityringer.domain.model.FailureReason
import com.elham.priorityringer.domain.model.Outcome
import com.elham.priorityringer.domain.model.PriorityContact
import com.elham.priorityringer.domain.port.AlertPort
import com.elham.priorityringer.presentation.PriorityAlertActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Full-screen escalation alert (FR5, Architecture.md § 8).
 *
 * Android 14 (API 34) stopped auto-granting `USE_FULL_SCREEN_INTENT` to every
 * app. Rather than reason about whether this app qualifies for the calling-app
 * exemption, this class simply *asks the platform* via
 * `canUseFullScreenIntent()` and degrades to a high-priority heads-up
 * notification when the answer is no. That is correct under either reading of
 * the policy, and the degradation is reported as
 * [AlertPort.AlertMode.HEADS_UP_FALLBACK] so the audit log and UI can say which
 * one the user actually got.
 *
 * Per § 8 the alert is **informational only**. It never attempts to answer or
 * reject the call — there is no supported API for that here, and reaching for
 * an unsupported one is out of scope by construction.
 */
@Singleton
class AndroidAlertPort @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notificationManager: NotificationManager,
) : AlertPort {

    /**
     * Created on first use rather than in `init`.
     *
     * This is a `@Singleton`, so an `init` block would run during Hilt graph
     * construction inside `Application.onCreate()` — doing platform work while
     * the process is still starting, for a channel that most launches never
     * need. `by lazy` is also thread-safe by default, which matters because the
     * first alert can be raised from the receiver's background scope.
     */
    private val channelCreated: Unit by lazy { createChannel() }

    override fun showPriorityAlert(contact: PriorityContact): Outcome<AlertPort.AlertMode> {
        channelCreated

        if (!hasPostNotificationsPermission()) {
            return Outcome.Failure(
                reason = FailureReason.RUNTIME_PERMISSION_DENIED,
                detail = "POST_NOTIFICATIONS not granted",
            )
        }

        val canUseFullScreen = canUseFullScreenIntent()
        val mode = if (canUseFullScreen) {
            AlertPort.AlertMode.FULL_SCREEN
        } else {
            AlertPort.AlertMode.HEADS_UP_FALLBACK
        }

        return try {
            notificationManager.notify(
                NOTIFICATION_ID,
                buildNotification(contact, canUseFullScreen),
            )
            Outcome.Success(mode)
        } catch (e: SecurityException) {
            Timber.w(e, "SecurityException posting priority alert")
            Outcome.Failure(FailureReason.RUNTIME_PERMISSION_DENIED, e.message, e)
        } catch (e: Exception) {
            Timber.e(e, "Failed to post priority alert")
            Outcome.Failure(FailureReason.UNKNOWN, e.message, e)
        }
    }

    /**
     * Cancels the notification **and** closes the alert activity if it is open.
     *
     * Cancelling the notification alone is not enough: once a full-screen
     * intent has launched [PriorityAlertActivity], that activity has its own
     * lifetime and would stay on screen after the call ended and the audio
     * state was restored — a stuck alert over the lock screen, which on a
     * family member's phone reads as the app having crashed.
     *
     * The broadcast is package-scoped so it reaches only our own non-exported
     * receiver.
     */
    override fun dismissAlert() {
        runCatching { notificationManager.cancel(NOTIFICATION_ID) }
            .onFailure { Timber.w(it, "Could not dismiss alert notification") }

        runCatching {
            context.sendBroadcast(
                Intent(PriorityAlertActivity.ACTION_DISMISS).setPackage(context.packageName),
            )
        }.onFailure { Timber.w(it, "Could not dismiss alert activity") }
    }

    private fun buildNotification(
        contact: PriorityContact,
        withFullScreen: Boolean,
    ): Notification {
        val intent = Intent(context, PriorityAlertActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(PriorityAlertActivity.EXTRA_CONTACT_NAME, contact.displayName)
            putExtra(PriorityAlertActivity.EXTRA_CONTACT_NUMBER, contact.redactedNumber)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            /* requestCode = */ 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_priority_call)
            .setContentTitle(context.getString(R.string.alert_title, contact.displayName))
            .setContentText(context.getString(R.string.alert_body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .apply {
                if (withFullScreen) {
                    setFullScreenIntent(pendingIntent, /* highPriority = */ true)
                }
            }
            .build()
    }

    /**
     * API 34+ gates this behind an explicit user allowance, surfaced on the
     * Permissions screen with a deep link to
     * `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`.
     */
    private fun canUseFullScreenIntent(): Boolean =
        if (Build.VERSION.SDK_INT >= 34) {
            runCatching { notificationManager.canUseFullScreenIntent() }.getOrDefault(false)
        } else {
            true
        }

    private fun hasPostNotificationsPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.alert_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.alert_channel_description)
            // The app raises the *ring* stream; the notification itself stays
            // silent so it cannot double up on the ringtone.
            setSound(null, null)
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        }
        runCatching { notificationManager.createNotificationChannel(channel) }
            .onFailure { Timber.w(it, "Could not create alert channel") }
    }

    private companion object {
        const val CHANNEL_ID = "priority_call_alert"
        const val NOTIFICATION_ID = 1001
    }
}
