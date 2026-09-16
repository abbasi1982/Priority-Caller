package com.elham.priorityringer.di

import javax.inject.Qualifier

/**
 * Dispatcher qualifiers (Architecture.md § 11 — `DispatcherModule` binds
 * `@Io`, `@Default`, `@Main`).
 *
 * Injected rather than referenced as `Dispatchers.IO` directly so tests can
 * substitute a `TestDispatcher`. The mutate-then-restore path is the code § 14
 * insists must be testable without a device, and that is only possible if its
 * threading is an injected value.
 */

@Retention(AnnotationRetention.BINARY)
@Qualifier
annotation class IoDispatcher

@Retention(AnnotationRetention.BINARY)
@Qualifier
annotation class DefaultDispatcher

@Retention(AnnotationRetention.BINARY)
@Qualifier
annotation class MainDispatcher
