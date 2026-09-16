package com.elham.priorityringer.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.elham.priorityringer.di.ApplicationScope
import com.elham.priorityringer.domain.model.CallState
import com.elham.priorityringer.domain.model.IncomingCallEvent
import com.elham.priorityringer.domain.phone.PhoneNumberNormalizer
import com.elham.priorityringer.domain.port.Clock
import com.elham.priorityringer.domain.port.RingtonePlayerPort
import com.elham.priorityringer.domain.port.TelephonyPort
import com.elham.priorityringer.domain.usecase.IncomingCallCoordinator
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Primary incoming-call detection (Architecture.md § 5.1).
 *
 * `EXTRA_INCOMING_NUMBER` is only populated when `READ_CALL_LOG` is granted
 * (Android 9+). That is official platform behaviour, and § 17.4 is explicit
 * that a missing number must be treated as "cannot identify caller" rather
 * than as a bug to work around. Without it this receiver still fires; it simply
 * has nothing to match, and says so.
 *
 * **Threading (§ A.2).** Work happens under [goAsync] with a hard timeout
 * rather than in a foreground service. The mutations are a handful of
 * synchronous `AudioManager` / `NotificationManager` calls plus a small Room
 * write, which fit comfortably inside the broadcast window, and this avoids a
 * `foregroundServiceType` the app cannot honestly justify — § 5.4 rules out
 * `FOREGROUND_SERVICE_TYPE_PHONE_CALL` because this is not a calling app.
 *
 * **Dependencies are resolved here, not injected by `@AndroidEntryPoint`**, for
 * the same reason as [BootCompletedReceiver]. That annotation injects in
 * generated code that runs *before* this class does, so a failure throws
 * straight out of `onReceive` with nothing this class can do about it — and §
 * 4 is explicit that an exception escaping here kills the app **during an
 * incoming call**. The rule was already "never throw out of `onReceive`"; it
 * has to cover obtaining the collaborators as much as using them.
 *
 * This is not theoretical. A `BOOT_COMPLETED` arriving mid-instrumentation
 * killed an entire test run with "The component was not created", because under
 * instrumentation the app runs on `HiltTestApplication`, which has no component
 * until a test installs one. The same is true here for any `PHONE_STATE` that
 * lands during an instrumented run — a real call on the test phone.
 *
 * In production the graph is always present, so this changes nothing about the
 * call path. It only decides what happens when there is no graph: a logged
 * no-op instead of a dead process.
 */
class PhoneStateReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun coordinator(): IncomingCallCoordinator
        fun normalizer(): PhoneNumberNormalizer
        fun telephonyPort(): TelephonyPort
        fun ringtonePlayer(): RingtonePlayerPort
        fun clock(): Clock

        @ApplicationScope
        fun applicationScope(): CoroutineScope
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val dependencies = runCatching {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                Dependencies::class.java,
            )
        }.getOrElse { error ->
            Timber.w(error, "No dependency graph available; ignoring this PHONE_STATE")
            return
        }

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)

        @Suppress("DEPRECATION")
        val rawNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> handleRinging(dependencies, rawNumber)

            // Both states restore. Belt-and-braces alongside the
            // TelephonyCallback path (§ 5.2): the callback only runs while the
            // process is alive, whereas this broadcast will restart it — which
            // is precisely the case where a snapshot is stranded on disk.
            TelephonyManager.EXTRA_STATE_OFFHOOK ->
                handleCallOver(dependencies, CallState.OFFHOOK)

            TelephonyManager.EXTRA_STATE_IDLE ->
                handleCallOver(dependencies, CallState.IDLE)
        }
    }

    /** The ringtone is over — answered or ended. Restore is idempotent. */
    private fun handleCallOver(dependencies: Dependencies, state: CallState) {
        // Stop the alarm-stream alert **first, synchronously, and outside the
        // coordinator**, before anything that can wait on a lock.
        //
        // `onCallStateChanged` takes the coordinator mutex to run the restore.
        // If a restore were already in flight — a watchdog firing, an
        // overlapping call — a stop routed through the coordinator would queue
        // behind it, and the alert would keep playing into the ear of someone
        // who has just answered the phone. There is nothing to serialise here
        // anyway: stopping playback is idempotent and touches no shared state
        // the restore cares about.
        runCatching { dependencies.ringtonePlayer().stopAlarmStreamAlert() }
            .onFailure { Timber.w(it, "Could not stop the alarm-stream alert") }

        val pending = goAsync()
        dependencies.applicationScope().launch {
            try {
                withTimeoutOrNull(WORK_TIMEOUT_MS) {
                    dependencies.coordinator().onCallStateChanged(state)
                }
            } catch (t: Throwable) {
                Timber.e(t, "Error restoring after call state %s", state)
            } finally {
                pending.finish()
            }
        }
    }

    private fun handleRinging(dependencies: Dependencies, rawNumber: String?) {
        val now = dependencies.clock().nowEpochMs()

        // § 5.1 — the system may deliver more than one RINGING broadcast for a
        // single call, one of them blank. Debounce so a duplicate cannot be
        // counted as a second call and trip escalation.
        if (isDuplicate(rawNumber, now)) {
            Timber.d("Ignoring duplicate RINGING broadcast")
            return
        }
        lastNumber = rawNumber
        lastRingingAtMs = now

        val blank = rawNumber.isNullOrBlank()
        if (!blank) lastNumberedRingingAtMs = now

        val pending = goAsync()
        dependencies.applicationScope().launch {
            try {
                // A blank delivery waits before it is allowed to claim the
                // caller is unidentifiable.
                //
                // Observed on a real phone: the *first* RINGING broadcast for a
                // call carried no number and the second, milliseconds later,
                // did. The debounce above only catches a blank arriving after a
                // numbered one, so the blank-first order logged "no caller
                // number — this normally means the Call Log permission is not
                // granted" about a call the app went on to identify correctly,
                // with the permission granted. A false statement about the
                // user's own permissions, in the log they are told to trust.
                //
                // Nothing is lost by waiting: a blank broadcast carries nothing
                // to act on, so the only thing deferred is the log entry.
                if (blank) {
                    delay(BLANK_NUMBER_GRACE_MS)
                    if (lastNumberedRingingAtMs > now) {
                        Timber.d("Blank RINGING superseded by a numbered delivery")
                        return@launch
                    }
                }

                withTimeoutOrNull(WORK_TIMEOUT_MS) {
                    dependencies.coordinator().onIncomingCall(
                        IncomingCallEvent(
                            number = dependencies.normalizer().normalize(
                                rawNumber,
                                dependencies.telephonyPort().defaultCountryIso(),
                            ),
                            timestampEpochMs = now,
                        ),
                    )
                } ?: Timber.w("Incoming-call handling timed out after %d ms", WORK_TIMEOUT_MS)
            } catch (t: Throwable) {
                // The coordinator already swallows and audits its own errors;
                // this is the last line of defence so nothing can escape into
                // the system's broadcast dispatch.
                Timber.e(t, "Error in PHONE_STATE receiver")
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * A blank-number broadcast immediately following a numbered one is the
     * system's second delivery, not a new call. Treating a blank as distinct
     * would raise a spurious `NUMBER_UNAVAILABLE` on every single call.
     */
    private fun isDuplicate(rawNumber: String?, nowMs: Long): Boolean {
        if (nowMs - lastRingingAtMs > DEBOUNCE_MS) return false
        return rawNumber.isNullOrBlank() || rawNumber == lastNumber
    }

    private companion object {
        /**
         * Comfortably inside the ~10s the system allows a background receiver,
         * while leaving room for a slow first Room access.
         */
        const val WORK_TIMEOUT_MS = 5_000L

        /**
         * Deliberately short.
         *
         * The duplicate this guards against is the system's own second RINGING
         * broadcast for a single call, which arrives within milliseconds. A
         * longer window would also swallow a genuine rapid redial — and a
         * repeat caller hanging up and immediately calling back is precisely
         * the input FR5 escalation exists to detect, so suppressing it would
         * defeat the feature. Real call setup takes seconds, so 500ms cannot
         * hide a second real call.
         */
        const val DEBOUNCE_MS = 500L

        /**
         * How long a blank RINGING waits for a numbered delivery of the same
         * call before it reports the caller as unidentifiable.
         *
         * Comfortably covers the observed gap (milliseconds) while staying far
         * inside [WORK_TIMEOUT_MS] and the system's broadcast window. It delays
         * only an audit entry — never a ring.
         */
        const val BLANK_NUMBER_GRACE_MS = 1_000L

        @Volatile
        var lastRingingAtMs: Long = 0L

        @Volatile
        var lastNumberedRingingAtMs: Long = 0L

        @Volatile
        var lastNumber: String? = null
    }
}
