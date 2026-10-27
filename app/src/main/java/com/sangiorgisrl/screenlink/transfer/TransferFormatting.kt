package com.sangiorgisrl.screenlink.transfer

import java.util.Locale

/**
 * Human-readable sizes, speeds and remaining time for the transfer progress UI.
 *
 * Pure and locale-explicit: the numbers are what a user reads while a file is
 * moving, so they must not depend on whatever default locale the process happens
 * to have (the app has its own in-app language picker, which does NOT always move
 * `Locale.getDefault()`). Callers pass the UI locale; tests pass one explicitly.
 */
object TransferFormatting {

    private val UNITS = arrayOf("B", "KB", "MB", "GB", "TB", "PB")

    /** 1024-stepped size, one decimal below 100: `977 B`, `1.5 KB`, `240 MB`. */
    fun bytes(value: Long, locale: Locale = Locale.getDefault()): String {
        if (value < 0L) return "?"
        if (value < 1024L) return "$value ${UNITS[0]}"
        var v = value.toDouble()
        var unit = 0
        while (v >= 1024.0 && unit < UNITS.size - 1) {
            v /= 1024.0
            unit++
        }
        val pattern = if (v < 100.0) "%.1f %s" else "%.0f %s"
        return String.format(locale, pattern, v, UNITS[unit])
    }

    /** `2.1 MB/s` - the unit separator "/" is the same in every locale. */
    fun rate(bytesPerSecond: Double, locale: Locale = Locale.getDefault()): String =
        bytes(bytesPerSecond.toLong(), locale) + "/s"

    /**
     * Remaining time, coarse on purpose: a transfer ETA is an estimate, so
     * `4 min` is a better promise than `3 min 47 s` that changes every second.
     * null / non-finite / unknown -> [UNKNOWN] so the UI can say "calculating".
     */
    fun duration(seconds: Double?, locale: Locale = Locale.getDefault()): String {
        if (seconds == null || seconds.isNaN() || seconds.isInfinite() || seconds < 0.0) return UNKNOWN
        val total = seconds.toLong()
        if (total < 60) return "$total s"
        val minutes = total / 60
        if (minutes < 60) {
            val secs = total % 60
            return if (secs == 0L) "$minutes min" else String.format(locale, "%d min %02d s", minutes, secs)
        }
        val hours = minutes / 60
        val rem = minutes % 60
        return String.format(locale, "%d h %02d min", hours, rem)
    }

    /** 0..100 for a determinate bar, or null when the total is unknown. */
    fun percent(transferredBytes: Long, totalBytes: Long): Int? {
        if (totalBytes <= 0L || transferredBytes < 0L) return null
        return ((transferredBytes * 100.0) / totalBytes).toInt().coerceIn(0, 100)
    }

    /** Fraction for a progress bar (0f..1f), or null = indeterminate. */
    fun fraction(transferredBytes: Long, totalBytes: Long): Float? {
        if (totalBytes <= 0L || transferredBytes < 0L) return null
        return (transferredBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
    }

    const val UNKNOWN = "\u2014"
}
