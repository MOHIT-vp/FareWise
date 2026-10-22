package com.farewise.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.farewise.app.data.local.entity.PricePredictionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PricePredictionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PricePredictionEntity): Long

    /** Latest prediction per (provider, serviceTier). Drives Phase-4 banners. */
    @Query(
        """
        SELECT * FROM price_prediction pp
        WHERE pp.generatedAtMillis = (
            SELECT MAX(generatedAtMillis) FROM price_prediction
            WHERE provider = pp.provider AND serviceTier = pp.serviceTier
        )
        """
    )
    fun observeLatestPerTier(): Flow<List<PricePredictionEntity>>

    @Query(
        """
        SELECT * FROM price_prediction
        WHERE provider = :provider AND serviceTier = :serviceTier
        ORDER BY generatedAtMillis DESC
        LIMIT 1
        """
    )
    suspend fun latestForTier(provider: String, serviceTier: String): PricePredictionEntity?

    @Query("DELETE FROM price_prediction WHERE generatedAtMillis < :cutoffMillis")
    suspend fun pruneOlderThan(cutoffMillis: Long)
}
