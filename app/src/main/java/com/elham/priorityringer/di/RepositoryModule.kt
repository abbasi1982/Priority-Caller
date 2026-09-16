package com.elham.priorityringer.di

import com.elham.priorityringer.data.repository.AuditRepositoryImpl
import com.elham.priorityringer.data.repository.ContactRepositoryImpl
import com.elham.priorityringer.data.repository.EscalationRepositoryImpl
import com.elham.priorityringer.data.repository.RestoreRepositoryImpl
import com.elham.priorityringer.data.repository.SettingsRepositoryImpl
import com.elham.priorityringer.domain.repository.AuditRepository
import com.elham.priorityringer.domain.repository.ContactRepository
import com.elham.priorityringer.domain.repository.EscalationRepository
import com.elham.priorityringer.domain.repository.RestoreRepository
import com.elham.priorityringer.domain.repository.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Architecture.md § 11 — `@Binds` of the domain repository interfaces.
 *
 * `@Binds` rather than `@Provides`: use cases must only ever see the interface
 * declared in `domain`, which is what keeps the § 2 dependency direction
 * (domain declares, data implements) enforced by the compiler.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindContactRepository(impl: ContactRepositoryImpl): ContactRepository

    @Binds
    @Singleton
    abstract fun bindAuditRepository(impl: AuditRepositoryImpl): AuditRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindRestoreRepository(impl: RestoreRepositoryImpl): RestoreRepository

    @Binds
    @Singleton
    abstract fun bindEscalationRepository(impl: EscalationRepositoryImpl): EscalationRepository
}
