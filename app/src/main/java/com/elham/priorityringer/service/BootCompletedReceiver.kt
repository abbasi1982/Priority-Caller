package com.elham.priorityringer.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.elham.priorityringer.di.ApplicationScope
import com.elham.priorityringer.domain.usecase.IncomingCallCoordinator
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Runs the cold-start reconciliation pass after a reboot (§ A.3, trigger 3).
 *
 * ## What this receiver is *not* for
 *
 * It is **not** what makes incoming calls detected. [PhoneStateReceiver] is
 * declared in the manifest, so the system starts this app to deliver
 * `PHONE_STATE` whether or not it is running, across reboots, with no boot
 * receiver and no background service involved. That is ordinary Android
 * behaviour and it is the whole detection design (§ 5.1, § A.2) — the app is
 * deliberately not a resident process.
 *
 * The one exception, which no app can work around: an app the user has
 * **force-stopped** receives no broadcasts at all until it is opened again.
 * That is a platform decision and applies to `PHONE_STATE` and `BOOT_COMPLETED`
 * alike.
 *
 * ## What it is for
 *
 * A reboot can strand a snapshot. If the phone was powered off (or crashed)
 * while a priority call had the ringer raised and Do Not Disturb relaxed, the
 * pending snapshot is still in Room, and nothing would read it until the *next*
 * call arrived — which could be days later, with the phone loud the whole time.
 *
 * Restoring audio state is also exactly the kind of work that must not be
 * guessed at from a half-booted device, so this deliberately handles
 * `ACTION_BOOT_COMPLETED` only, not `ACTION_LOCKED_BOOT_COMPLETED`: the
 * snapshot lives in credential-encrypted storage, which is not readable before
 * the user's first unlock. Waiting is correct, not a limitation to route
 * around.
 *
 * `Application.onCreate` also kicks off a reconciliation, and creating this
 * receiver is itself what creates the `Application` — so the pass would happen
 * regardless. The explicit call here exists for a different reason: `goAsync()`
 * keeps the process alive until it finishes. Without it the process may be torn
 * down mid-restore, which is the failure this whole subsystem exists to
 * prevent. The pass is idempotent and serialised by the coordinator's mutex, so
 * running it twice costs one no-op database read.
 */
@AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {

    @Inject lateinit var coordinator: IncomingCallCoordinator

    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Timber.i("Boot completed; reconciling any stranded audio snapshot")

        val pending = goAsync()
        scope.launch {
            try {
                withTimeoutOrNull(WORK_TIMEOUT_MS) {
                    coordinator.reconcileStaleRestore()
                } ?: Timber.w("Boot reconciliation timed out after %d ms", WORK_TIMEOUT_MS)
            } catch (t: Throwable) {
                // Never let this escape into the system's boot broadcast
                // dispatch. A crash here would be attributed to the device
                // starting up, which is the worst possible place to be noisy.
                Timber.e(t, "Error reconciling after boot")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        /**
         * Longer than [PhoneStateReceiver]'s window: boot is the slowest moment
         * in a device's life and the first Room open has to compete with every
         * other app doing its own start-up work. Still inside the ~10s the
         * system allows a background receiver.
         */
        const val WORK_TIMEOUT_MS = 8_000L
    }
}
