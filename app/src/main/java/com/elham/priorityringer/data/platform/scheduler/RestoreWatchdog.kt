package com.elham.priorityringer.data.platform.scheduler

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.elham.priorityringer.domain.port.SchedulerPort
import com.elham.priorityringer.domain.usecase.IncomingCallCoordinator
import com.elham.priorityringer.domain.usecase.RestoreTrigger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * The watchdog half of the restore guarantee (Architecture.md § A.2, § A.3).
 *
 * WorkManager rather than a coroutine timer precisely because it survives
 * process death: the scenario this defends against is the app being killed
 * mid-call, which would take any in-process timer with it and leave the phone
 * off DND at raised volume indefinitely.
 */
@Singleton
class WorkManagerSchedulerPort @Inject constructor(
    @ApplicationContext private val context: Context,
) : SchedulerPort {

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    override fun scheduleRestoreWatchdog(afterSeconds: Int) {
        val request = OneTimeWorkRequestBuilder<RestoreWatchdogWorker>()
            .setInitialDelay(afterSeconds.toLong(), TimeUnit.SECONDS)
            .addTag(WORK_TAG)
            .build()

        // REPLACE: a newer call's deadline supersedes an older one. The pending
        // snapshot itself is never replaced (§ A.3 insert-if-absent) — only the
        // timer moves, so the original pre-mutation state is still what gets
        // restored.
        runCatching {
            workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }.onFailure { Timber.e(it, "Could not schedule restore watchdog") }
    }

    override fun cancelRestoreWatchdog() {
        runCatching { workManager.cancelUniqueWork(WORK_NAME) }
            .onFailure { Timber.w(it, "Could not cancel restore watchdog") }
    }

    companion object {
        const val WORK_NAME = "priority_ringer_restore_watchdog"
        const val WORK_TAG = "restore_watchdog"
    }
}

/**
 * Fires when a call-end signal never arrived.
 *
 * Always returns [Result.success]: a retry would re-run a restore that
 * the restore path has already made idempotent, and a `Result.retry`
 * loop against a device that is refusing the change would achieve nothing but
 * battery drain. Whether the restore actually worked is recorded in the audit
 * log, which is where the user can see it.
 */
@HiltWorker
class RestoreWatchdogWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val coordinator: IncomingCallCoordinator,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Timber.i("Restore watchdog fired")
        // Via the coordinator, not the use case directly, so this takes the
        // same lock an apply does. Timing alone should keep them apart — the
        // watchdog fires on the same deadline the cold-start expiry gate uses —
        // but that is an argument from scheduling, and it stops holding the
        // moment someone changes a timeout.
        runCatching { coordinator.restoreNow(RestoreTrigger.WATCHDOG_TIMEOUT) }
            .onFailure { Timber.e(it, "Watchdog restore failed") }
        return Result.success()
    }
}
