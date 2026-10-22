package com.farewise.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.farewise.app.data.local.entity.PriceHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PriceHistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<PriceHistoryEntity>)

    /**
     * Returns the history for one (provider, tier) since [sinceMillis],
     * ordered oldest → newest so the caller can feed it straight into the
     * model window without reversing.
     */
    @Query(
        """
        SELECT * FROM price_history
        WHERE provider = :provider
          AND serviceTier = :serviceTier
          AND fetchedAtMillis >= :sinceMillis
        ORDER BY fetchedAtMillis ASC
        """
    )
    suspend fun historyForTier(
        provider: String,
        serviceTier: String,
        sinceMillis: Long
    ): List<PriceHistoryEntity>

    /** Distinct (provider, tier) pairs seen in the window — drives worker iteration. */
    @Query(
        """
        SELECT DISTINCT provider, serviceTier
        FROM price_history
        WHERE fetchedAtMillis >= :sinceMillis
        """
    )
    suspend fun distinctTiersSince(sinceMillis: Long): List<TierKey>

    @Query("DELETE FROM price_history WHERE fetchedAtMillis < :cutoffMillis")
    suspend fun pruneOlderThan(cutoffMillis: Long)

    @Query("SELECT COUNT(*) FROM price_history")
    fun observeRowCount(): Flow<Int>

    /**
     * Stream every (provider, tier, price) point newer than [sinceMillis],
     * ordered oldest → newest. Emits a fresh list whenever the table changes
     * — Room's reactive Flow handles that automatically.
     *
     * The dashboard ViewModel groups these into `Map<TierKey, List<Double>>`
     * to drive a sparkline beside each ride card. We project only the columns
     * the UI needs so large rolling windows don't shovel useless bytes into
     * memory (basePriceInr / surgeMultiplier never reach the chart).
     */
    @Query(
        """
        SELECT provider, serviceTier, priceInr, fetchedAtMillis
        FROM price_history
        WHERE fetchedAtMillis >= :sinceMillis
        ORDER BY fetchedAtMillis ASC
        """
    )
    fun observeRecentPrices(sinceMillis: Long): Flow<List<RecentPriceRow>>

    data class TierKey(val provider: String, val serviceTier: String)

    data class RecentPriceRow(
        val provider: String,
        val serviceTier: String,
        val priceInr: Double,
        val fetchedAtMillis: Long
    )
}
