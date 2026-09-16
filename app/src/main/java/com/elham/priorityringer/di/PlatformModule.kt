package com.elham.priorityringer.di

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.telephony.TelephonyManager
import androidx.core.content.getSystemService
import com.elham.priorityringer.data.platform.SystemClock
import com.elham.priorityringer.data.platform.alert.AndroidAlertPort
import com.elham.priorityringer.data.platform.audio.AndroidAudioPort
import com.elham.priorityringer.data.platform.audio.AndroidRingtonePlayerPort
import com.elham.priorityringer.data.platform.capability.AndroidCapabilityPort
import com.elham.priorityringer.data.platform.dnd.AndroidDndPort
import com.elham.priorityringer.data.platform.scheduler.WorkManagerSchedulerPort
import com.elham.priorityringer.data.platform.telephony.AndroidTelephonyPort
import com.elham.priorityringer.domain.escalation.EscalationPolicy
import com.elham.priorityringer.domain.phone.PhoneNumberNormalizer
import com.elham.priorityringer.domain.port.AlertPort
import com.elham.priorityringer.domain.port.AudioPort
import com.elham.priorityringer.domain.port.CapabilityPort
import com.elham.priorityringer.domain.port.Clock
import com.elham.priorityringer.domain.port.DndPort
import com.elham.priorityringer.domain.port.RingtonePlayerPort
import com.elham.priorityringer.domain.port.SchedulerPort
import com.elham.priorityringer.domain.port.TelephonyPort
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the Android seam (Architecture.md § 11, § A.4).
 *
 * Everything the use cases can reach is an interface from `domain/port`.
 * Swapping any of these for a fake — which the unit tests do — requires no
 * change to a single use case.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PlatformModule {

    @Binds
    @Singleton
    abstract fun bindAudioPort(impl: AndroidAudioPort): AudioPort

    @Binds
    @Singleton
    abstract fun bindDndPort(impl: AndroidDndPort): DndPort

    @Binds
    @Singleton
    abstract fun bindTelephonyPort(impl: AndroidTelephonyPort): TelephonyPort

    @Binds
    @Singleton
    abstract fun bindAlertPort(impl: AndroidAlertPort): AlertPort

    @Binds
    @Singleton
    abstract fun bindRingtonePlayerPort(impl: AndroidRingtonePlayerPort): RingtonePlayerPort

    @Binds
    @Singleton
    abstract fun bindSchedulerPort(impl: WorkManagerSchedulerPort): SchedulerPort

    @Binds
    @Singleton
    abstract fun bindCapabilityPort(impl: AndroidCapabilityPort): CapabilityPort

    @Binds
    @Singleton
    abstract fun bindClock(impl: SystemClock): Clock

    companion object {

        @Provides
        @Singleton
        fun provideAudioManager(@ApplicationContext context: Context): AudioManager =
            requireNotNull(context.getSystemService()) { "AudioManager unavailable" }

        @Provides
        @Singleton
        fun provideNotificationManager(
            @ApplicationContext context: Context,
        ): NotificationManager =
            requireNotNull(context.getSystemService()) { "NotificationManager unavailable" }

        @Provides
        @Singleton
        fun provideTelephonyManager(@ApplicationContext context: Context): TelephonyManager =
            requireNotNull(context.getSystemService()) { "TelephonyManager unavailable" }

        /** Stateless pure-domain helpers; no reason for more than one of each. */
        @Provides
        @Singleton
        fun providePhoneNumberNormalizer(): PhoneNumberNormalizer = PhoneNumberNormalizer()

        @Provides
        @Singleton
        fun provideEscalationPolicy(): EscalationPolicy = EscalationPolicy()
    }
}
