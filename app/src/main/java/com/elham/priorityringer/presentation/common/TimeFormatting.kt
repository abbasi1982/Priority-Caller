package com.elham.priorityringer.presentation.common

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Epoch millis → local wall-clock text.
 *
 * `java.time` is unconditionally available: `minSdk` is 30 and the API landed
 * in 26, so no desugaring dependency is required.
 */
object TimeFormatting {

    private val timeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

    private val dateTimeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

    fun timestamp(epochMs: Long, locale: Locale = Locale.getDefault()): String =
        dateTimeFormatter
            .withLocale(locale)
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(epochMs))

    fun timeOnly(epochMs: Long, locale: Locale = Locale.getDefault()): String =
        timeFormatter
            .withLocale(locale)
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(epochMs))

    /** Calendar day key, used to group the audit log under date headers. */
    fun dayKey(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    fun dayLabel(epochMs: Long, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
            .withLocale(locale)
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(epochMs))
}
