package com.elham.priorityringer.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.elham.priorityringer.di.ApplicationScope
import com.elham.priorityringer.domain.usecase.IncomingCallCoordinator
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
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
 * `Application.onCreate` also kicks off a reconciliation, and resolving
 * dependencies here is itself what creates the `Application` — so the pass
 * would happen regardless. The explicit call exists for a different reason:
 * `goAsync()` keeps the process alive until it finishes. Without it the process
 * may be torn down mid-restore, which is the failure this whole subsystem
 * exists to prevent. The pass is idempotent and serialised by the coordinator's
 * mutex, so running it twice costs one no-op database read.
 *
 * ## Why this resolves its own dependencies instead of using `@AndroidEntryPoint`
 *
 * `@AndroidEntryPoint` injects in generated code that runs *before* this class
 * does, so a failure there throws straight out of `onReceive` with nothing this
 * class can do about it. That is not hypothetical: under instrumentation the app
 * runs on `HiltTestApplication`, which has no component until a test installs
 * one, and a `BOOT_COMPLETED` arriving mid-test killed the whole instrumentation
 * run with "The component was not created".
 *
 * A receiver in this app must never throw out of `onReceive` (§ 4, § A.2) — the
 * rule already applied to the work, and it applies just as much to obtaining the
 * things that do it. Resolving the graph by hand makes that expressible: no
 * graph means a logged no-op, and the reboot pass is skipped rather than the
 * process dying. In production the graph is always there.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun coordinator(): IncomingCallCoordinator

        @ApplicationScope
        fun applicationScope(): CoroutineScope
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val dependencies = runCatching {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                Dependencies::class.java,
            )
        }.getOrElse { error ->
            Timber.w(error, "No dependency graph available; skipping the reboot restore pass")
            return
        }

        Timber.i("Boot completed; reconciling any stranded audio snapshot")

        val pending = goAsync()
        dependencies.applicationScope().launch {
            try {
                withTimeoutOrNull(WORK_TIMEOUT_MS) {
                    dependencies.coordinator().reconcileStaleRestore()
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
