package com.farewise.app.di

import com.farewise.app.data.location.FusedLocationProviderImpl
import com.farewise.app.data.ml.FareEstimatorImpl
import com.farewise.app.data.ml.SurgePredictorImpl
import com.farewise.app.data.repository.AndroidGeocodingRepository
import com.farewise.app.data.repository.BookingRepositoryImpl
import com.farewise.app.data.repository.GoogleTransitRepository
import com.farewise.app.data.repository.LastMileEstimatorImpl
import com.farewise.app.data.repository.OpenWeatherRepository
import com.farewise.app.data.repository.PriceHistoryRepositoryImpl
import com.farewise.app.data.repository.SmartRideDataRepository
import com.farewise.app.domain.repository.BookingRepository
import com.farewise.app.domain.repository.FareEstimator
import com.farewise.app.domain.repository.GeocodingRepository
import com.farewise.app.domain.repository.LastMileEstimator
import com.farewise.app.domain.repository.LocationProvider
import com.farewise.app.domain.repository.PriceHistoryRepository
import com.farewise.app.domain.repository.RideDataRepository
import com.farewise.app.domain.repository.SurgePredictionRepository
import com.farewise.app.domain.repository.TransitRepository
import com.farewise.app.domain.repository.WeatherRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds repository interfaces to their concrete implementations.
 *
 * Swapping a Phase 1 mock for a real (HTTP + Room-cached) implementation
 * later only requires changing the @Binds target here — no call sites move.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindRideDataRepository(
        impl: SmartRideDataRepository
    ): RideDataRepository

    @Binds
    @Singleton
    abstract fun bindTransitRepository(
        impl: GoogleTransitRepository
    ): TransitRepository

    @Binds
    @Singleton
    abstract fun bindPriceHistoryRepository(
        impl: PriceHistoryRepositoryImpl
    ): PriceHistoryRepository

    @Binds
    @Singleton
    abstract fun bindSurgePredictionRepository(
        impl: SurgePredictorImpl
    ): SurgePredictionRepository

    @Binds
    @Singleton
    abstract fun bindFareEstimator(
        impl: FareEstimatorImpl
    ): FareEstimator

    @Binds
    @Singleton
    abstract fun bindWeatherRepository(
        impl: OpenWeatherRepository
    ): WeatherRepository

    @Binds
    @Singleton
    abstract fun bindBookingRepository(
        impl: BookingRepositoryImpl
    ): BookingRepository

    @Binds
    @Singleton
    abstract fun bindLastMileEstimator(
        impl: LastMileEstimatorImpl
    ): LastMileEstimator

    @Binds
    @Singleton
    abstract fun bindLocationProvider(
        impl: FusedLocationProviderImpl
    ): LocationProvider

    @Binds
    @Singleton
    abstract fun bindGeocodingRepository(
        impl: AndroidGeocodingRepository
    ): GeocodingRepository
}
