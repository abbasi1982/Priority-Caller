package com.elham.priorityringer

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.elham.priorityringer.di.ApplicationScope
import com.elham.priorityringer.domain.port.TelephonyPort
import com.elham.priorityringer.domain.usecase.IncomingCallCoordinator
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

@HiltAndroidApp
class PriorityRingerApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var coordinator: IncomingCallCoordinator

    @Inject lateinit var telephonyPort: TelephonyPort

    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(ReleaseTree())
        }

        reconcileStaleRestore()
        observeCallState()
    }

    /**
     * Restore trigger 3 (Architecture.md § A.3).
     *
     * If the process was killed mid-call, a `PendingRestore` row is still on
     * disk and the device is still sitting at raised volume with DND relaxed.
     * Running this on every start is what stops that state from persisting
     * indefinitely — it is a no-op in the overwhelmingly common case where
     * nothing is pending.
     */
    private fun reconcileStaleRestore() {
        scope.launch {
            runCatching { coordinator.reconcileStaleRestore() }
                .onFailure { Timber.e(it, "Stale-restore reconciliation failed") }
        }
    }

    /**
     * Restore trigger 1 — call state (§ 5.2).
     *
     * Only active while the process is alive, which is precisely why it is not
     * the only trigger.
     */
    private fun observeCallState() {
        scope.launch {
            runCatching {
                // `collect`, never `collectLatest`.
                //
                // collectLatest cancels the previous collector body when a new
                // value arrives. Here that body is a restore — so a RINGING ->
                // OFFHOOK -> IDLE sequence could cancel a restore part-way
                // through, after it had put the ringer back but before the DND
                // filter, leaving the device half-restored with its snapshot
                // already cleared.
                //
                // Restores are short and idempotent, so processing every state
                // in order is both correct and cheap.
                telephonyPort.callState().collect { state ->
                    coordinator.onCallStateChanged(state)
                }
            }.onFailure { Timber.e(it, "Call-state observation stopped") }
        }
    }
}

/**
 * Release logging.
 *
 * Drops debug/verbose and never writes phone numbers to logcat — Architecture.md
 * § 13 restricts logged numbers to a truncated form, and the domain models only
 * expose `redacted` variants for exactly this reason. Warnings and errors are
 * kept so a bug report from a family member's phone is still diagnosable; the
 * user-facing record is the in-app audit log, not logcat.
 */
private class ReleaseTree : Timber.Tree() {
    override fun isLoggable(tag: String?, priority: Int): Boolean =
        priority >= android.util.Log.WARN

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (!isLoggable(tag, priority)) return
        android.util.Log.println(priority, tag ?: "PriorityRinger", message)
        t?.let { android.util.Log.println(priority, tag ?: "PriorityRinger", it.toString()) }
    }
}
