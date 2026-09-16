package com.elham.priorityringer.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import timber.log.Timber

/** Process-lifetime scope, outliving any one screen or receiver. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppScopeModule {

    /**
     * Scope for work that must finish even if the component that started it
     * goes away — chiefly the `goAsync()` handling in `PhoneStateReceiver`,
     * which outlives the receiver instance itself.
     *
     * [SupervisorJob] so one failed call never cancels the scope and silently
     * disables detection for the rest of the process's life. The handler is the
     * final backstop: an uncaught throw here would otherwise reach the default
     * handler and crash the app mid-call.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.Default +
            CoroutineExceptionHandler { _, throwable ->
                Timber.e(throwable, "Uncaught error in application scope")
            },
    )
}
