package com.campmeds.app.domain

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

/** Result of validating user-typed text. Never silently drops or "fixes" bad input. */
sealed class Validated<out T> {
    data class Ok<T>(val value: T) : Validated<T>()
    data class Invalid(val message: String) : Validated<Nothing>()
}

/**
 * Pure (no Android dependencies) input validation for the Add/Edit Medication form.
 *
 * V1 bugs 3-6: the old code used runCatching{...}.getOrNull() which silently discarded bad schedule
 * times and bad dates. Here every entry must parse or the whole field is rejected with a message
 * that quotes the offending text, and the caller keeps the user's text so they can correct it.
 */
object InputValidation {

    /** Accepts "8:00" and "08:00" (24h). Rejects "25:00", "8 AM", "8:5", "24:00". */
    private val TIME_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("H:mm").withResolverStyle(ResolverStyle.STRICT)

    const val TIME_HELP = "Use HH:MM format, such as 08:00."
    const val DATE_HELP = "Use YYYY-MM-DD, such as 2026-07-15."

    /**
     * Parses a comma-separated list of 24h times. Every entry must be valid and at least one entry is
     * required (V1 bug 4). Result is sorted and de-duplicated: two identical times would otherwise
     * produce two identical rows (same key) on Today's Due.
     */
    fun parseScheduleTimes(text: String): Validated<List<LocalTime>> {
        if (text.isBlank()) {
            return Validated.Invalid("Enter at least one schedule time. $TIME_HELP")
        }
        val times = mutableListOf<LocalTime>()
        for (raw in text.split(",")) {
            val piece = raw.trim()
            if (piece.isEmpty()) {
                return Validated.Invalid("Empty time entry (extra comma?). $TIME_HELP")
            }
            try {
                times += LocalTime.parse(piece, TIME_FORMAT)
            } catch (e: DateTimeParseException) {
                return Validated.Invalid("Invalid time: \"$piece\". $TIME_HELP")
            }
        }
        return Validated.Ok(times.distinct().sorted())
    }

    /** Parses one ISO date. Blank is allowed only when [required] is false (returns Ok(null)). */
    fun parseDate(text: String, label: String, required: Boolean): Validated<LocalDate?> {
        val piece = text.trim()
        if (piece.isEmpty()) {
            return if (required) Validated.Invalid("$label is required. $DATE_HELP") else Validated.Ok(null)
        }
        return try {
            Validated.Ok(LocalDate.parse(piece)) // ISO_LOCAL_DATE is strict: rejects 2026-09-31
        } catch (e: DateTimeParseException) {
            Validated.Invalid("Invalid $label: \"$piece\". $DATE_HELP")
        }
    }

    /** V1 bug 6: endDate == null || endDate >= startDate. Returns an error message or null if OK. */
    fun validateDateRange(start: LocalDate, end: LocalDate?): String? =
        if (end != null && end.isBefore(start)) "End date must be on or after the start date." else null
}
