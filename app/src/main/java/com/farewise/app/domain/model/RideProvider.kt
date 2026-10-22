package com.farewise.app.domain.model

/**
 * Supported ride-hailing providers surfaced in the unified dashboard.
 *
 * The [displayName] is intentionally the consumer-facing brand name so UI
 * code never has to translate enum names at render time.
 */
enum class RideProvider(val displayName: String) {
    UBER("Uber"),
    OLA("Ola"),
    RAPIDO("Rapido");

    companion object {
        fun fromDisplayName(name: String): RideProvider? =
            entries.firstOrNull { it.displayName.equals(name, ignoreCase = true) }
    }
}
