package com.farewise.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One booking the rider actually launched via the deep-linker.
 *
 * We capture this row inside [com.farewise.app.ui.dashboard.DashboardViewModel.onRideClicked]
 * — the same callback that hands the rider off to the partner app — so the
 * journal reflects "what the rider committed to", not "what the dashboard
 * showed". The user might pick a different tier inside Uber/Ola/Rapido after
 * the hand-off; we have no way to observe that. The booking row therefore
 * represents the rider's *intent* at hand-off.
 *
 * The two counterfactual columns ([predictedAlternativeInr], [activeAdviceKind])
 * exist so the journal can say things like "you saved ₹X by following the
 * BOOK NOW advice" or "you ignored a WAIT advice and paid ₹Y extra". Both
 * are nullable because most ticks have no advice attached.
 *
 * Indexed on `bookedAtMillis` so the monthly aggregation can range-scan
 * cheaply without a full table sort.
 */
@Entity(
    tableName = "booking",
    indices = [
        Index(value = ["bookedAtMillis"], name = "idx_booking_time")
    ]
)
data class BookingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val provider: String,
    val serviceTier: String,
    val priceInr: Double,
    val basePriceInr: Double,
    val surgeMultiplier: Double,
    val distanceKm: Double,
    val tripMinutes: Long,
    val originLabel: String?,
    val destinationLabel: String?,
    val bookedAtMillis: Long,
    /**
     * What the surge predictor thought the price for this tier would be 30
     * minutes after the booking, snapshotted at hand-off time. Null when no
     * forecast was available (cold start, missing assets).
     */
    val predictedAlternativeInr: Double?,
    /**
     * Name of [com.farewise.app.domain.model.RideAdvice.Kind] active at booking
     * time, or null if no banner was showing. Stored as a string instead of
     * a join because the Kind enum is cheap to round-trip and we never query
     * across both tables.
     */
    val activeAdviceKind: String?
)
