package com.farewise.app.di

import android.content.Context
import androidx.room.Room
import com.farewise.app.data.local.FareWiseDatabase
import com.farewise.app.data.local.dao.BookingDao
import com.farewise.app.data.local.dao.PriceHistoryDao
import com.farewise.app.data.local.dao.PricePredictionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the singleton Room database and its DAOs.
 *
 * fallbackToDestructiveMigration is intentional for Phase 3 — the only data
 * we keep is recent (≤ 4 h) price history, which the worker can rebuild
 * from live polling within minutes. Trading durability for zero migration
 * boilerplate is the right call while the schema is still moving.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): FareWiseDatabase =
        Room.databaseBuilder(context, FareWiseDatabase::class.java, FareWiseDatabase.NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun providePriceHistoryDao(db: FareWiseDatabase): PriceHistoryDao = db.priceHistoryDao()

    @Provides
    fun providePricePredictionDao(db: FareWiseDatabase): PricePredictionDao = db.pricePredictionDao()

    @Provides
    fun provideBookingDao(db: FareWiseDatabase): BookingDao = db.bookingDao()
}
