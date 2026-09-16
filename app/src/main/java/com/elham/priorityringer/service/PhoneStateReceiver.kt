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
import com.elham.priorityringer.domain.port.TelephonyPort
import com.elham.priorityringer.domain.usecase.IncomingCallCoordinator
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
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
 */
@AndroidEntryPoint
class PhoneStateReceiver : BroadcastReceiver() {

    @Inject lateinit var coordinator: IncomingCallCoordinator

    @Inject lateinit var normalizer: PhoneNumberNormalizer

    @Inject lateinit var telephonyPort: TelephonyPort

    @Inject lateinit var clock: Clock

    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)

        @Suppress("DEPRECATION")
        val rawNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> handleRinging(rawNumber)

            // Both states restore. Belt-and-braces alongside the
            // TelephonyCallback path (§ 5.2): the callback only runs while the
            // process is alive, whereas this broadcast will restart it — which
            // is precisely the case where a snapshot is stranded on disk.
            TelephonyManager.EXTRA_STATE_OFFHOOK -> handleCallOver(CallState.OFFHOOK)

            TelephonyManager.EXTRA_STATE_IDLE -> handleCallOver(CallState.IDLE)
        }
    }

    /** The ringtone is over — answered or ended. Restore is idempotent. */
    private fun handleCallOver(state: CallState) {
        val pending = goAsync()
        scope.launch {
            try {
                withTimeoutOrNull(WORK_TIMEOUT_MS) {
                    coordinator.onCallStateChanged(state)
                }
            } catch (t: Throwable) {
                Timber.e(t, "Error restoring after call state %s", state)
            } finally {
                pending.finish()
            }
        }
    }

    private fun handleRinging(rawNumber: String?) {
        val now = clock.nowEpochMs()

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
        scope.launch {
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
                    coordinator.onIncomingCall(
                        IncomingCallEvent(
                            number = normalizer.normalize(
                                rawNumber,
                                telephonyPort.defaultCountryIso(),
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
