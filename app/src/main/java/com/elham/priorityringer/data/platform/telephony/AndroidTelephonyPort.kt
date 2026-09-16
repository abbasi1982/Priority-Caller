package com.elham.priorityringer.data.platform.telephony

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneNumberUtils
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.elham.priorityringer.domain.model.CallState
import com.elham.priorityringer.domain.port.TelephonyPort
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import timber.log.Timber

/**
 * Telephony state and number utilities (Architecture.md § 5.2, § 5.3).
 *
 * This port is **state-only**. `TelephonyCallback.CallStateListener` carries no
 * caller number — AOSP omits it deliberately — so it can drive restore but can
 * never identify a caller. Identification comes from the `PHONE_STATE`
 * broadcast, which needs `READ_CALL_LOG` (§ A.5).
 */
@Singleton
class AndroidTelephonyPort @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telephonyManager: TelephonyManager,
) : TelephonyPort {

    private fun hasPhoneStatePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Emits call state for the restore trigger (§ A.3, trigger 1).
     *
     * If `READ_PHONE_STATE` is missing we emit nothing rather than throwing —
     * the watchdog and cold-start triggers still cover restore, so a missing
     * permission degrades the timing of restore, not its guarantee.
     */
    @SuppressLint("MissingPermission")
    override fun callState(): Flow<CallState> {
        if (!hasPhoneStatePermission()) {
            Timber.w("READ_PHONE_STATE not granted; call-state restore trigger unavailable")
            return flowOf()
        }

        return callbackFlow {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        trySend(state.toDomain())
                    }
                }
                telephonyManager.registerTelephonyCallback(context.mainExecutor, callback)
                awaitClose { telephonyManager.unregisterTelephonyCallback(callback) }
            } else {
                // API 30 only. Deprecated on 31+, which is why it is branched
                // rather than used everywhere.
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        trySend(state.toDomain())
                    }
                }
                @Suppress("DEPRECATION")
                telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                awaitClose {
                    @Suppress("DEPRECATION")
                    telephonyManager.listen(listener, PhoneStateListener.LISTEN_NONE)
                }
            }
        }.distinctUntilChanged()
    }

    /**
     * § 5.3 — network ISO first, then SIM, then device locale.
     *
     * Network before SIM because a roaming phone's SIM country is the wrong
     * frame for interpreting a locally-dialled number.
     */
    override fun defaultCountryIso(): String? = try {
        telephonyManager.networkCountryIso?.takeIf { it.isNotBlank() }
            ?: telephonyManager.simCountryIso?.takeIf { it.isNotBlank() }
            ?: Locale.getDefault().country.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        Timber.w(e, "Could not determine country ISO")
        null
    }?.uppercase(Locale.ROOT)

    /**
     * The platform's opinion on number equality (§ 5.3).
     *
     * Layered *after* the pure matcher, so it can only turn a non-match into a
     * match. Keeping it in this position is what allows the decisive matching
     * rules to stay JVM-testable.
     */
    override fun platformNumbersMatch(a: String, b: String): Boolean = try {
        PhoneNumberUtils.compare(context, a, b)
    } catch (e: Exception) {
        Timber.w(e, "PhoneNumberUtils.compare failed")
        false
    }

    private fun Int.toDomain(): CallState = when (this) {
        TelephonyManager.CALL_STATE_RINGING -> CallState.RINGING
        TelephonyManager.CALL_STATE_OFFHOOK -> CallState.OFFHOOK
        else -> CallState.IDLE
    }
}
