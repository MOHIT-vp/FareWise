package com.farewise.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.farewise.app.data.local.dao.BookingDao
import com.farewise.app.data.local.dao.PriceHistoryDao
import com.farewise.app.data.local.dao.PricePredictionDao
import com.farewise.app.data.local.entity.BookingEntity
import com.farewise.app.data.local.entity.PriceHistoryEntity
import com.farewise.app.data.local.entity.PricePredictionEntity

/**
 * On-device cache for observed ride prices and the TFLite predictor's
 * forecasts.
 *
 * Phase 3 keeps the schema intentionally small — two independent tables,
 * no foreign keys, no converters. Adding migrations for future columns is
 * straightforward because nothing joins these tables at the SQL layer.
 */
@Database(
    entities = [
        PriceHistoryEntity::class,
        PricePredictionEntity::class,
        BookingEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class FareWiseDatabase : RoomDatabase() {
    abstract fun priceHistoryDao(): PriceHistoryDao
    abstract fun pricePredictionDao(): PricePredictionDao
    abstract fun bookingDao(): BookingDao

    companion object {
        const val NAME = "farewise.db"
    }
}
