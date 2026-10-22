package com.farewise.app.ui.common

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Presentation helpers — kept as plain top-level functions for testability. */
object Formatters {

    private val CLOCK = DateTimeFormatter
        .ofPattern("h:mm a", Locale.ENGLISH)
        .withZone(ZoneId.systemDefault())

    fun inr(amount: Double): String =
        "₹${"%.0f".format(Locale.ENGLISH, amount)}"

    /**
     * Compact duration string:
     *   • "4 min"
     *   • "1 hr 12 min"
     * Negative or zero durations collapse to "now".
     */
    fun duration(d: Duration): String {
        val seconds = d.seconds
        if (seconds <= 0) return "now"
        val mins = (seconds + 30) / 60  // round-to-nearest minute
        return when {
            mins < 60 -> "$mins min"
            mins % 60 == 0L -> "${mins / 60} hr"
            else -> "${mins / 60} hr ${mins % 60} min"
        }
    }

    fun clock(instant: Instant): String = CLOCK.format(instant)

    fun distance(km: Double): String =
        if (km < 1.0) "${(km * 1000).toInt()} m"
        else "%.1f km".format(Locale.ENGLISH, km)
}
