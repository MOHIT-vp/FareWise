package com.farewise.app.data.repository

import com.farewise.app.data.local.dao.PriceHistoryDao
import com.farewise.app.data.local.entity.PriceHistoryEntity
import com.farewise.app.domain.model.RideOption
import com.farewise.app.domain.model.RideProvider
import com.farewise.app.domain.repository.PriceHistoryRepository
import com.farewise.app.domain.repository.PriceHistoryRepository.HistoryPoint
import com.farewise.app.domain.repository.PriceHistoryRepository.TierRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PriceHistoryRepositoryImpl @Inject constructor(
    private val dao: PriceHistoryDao
) : PriceHistoryRepository {

    override suspend fun record(options: List<RideOption>) {
        if (options.isEmpty()) return
        dao.insertAll(options.map { it.toEntity() })
    }

    override suspend fun windowFor(
        provider: RideProvider,
        serviceTier: String,
        sinceMillis: Long
    ): List<HistoryPoint> =
        dao.historyForTier(provider.name, serviceTier, sinceMillis).map {
            HistoryPoint(
                priceInr = it.priceInr,
                surgeMultiplier = it.surgeMultiplier,
                fetchedAtMillis = it.fetchedAtMillis
            )
        }

    override suspend fun distinctTiersSince(sinceMillis: Long): List<TierRef> =
        dao.distinctTiersSince(sinceMillis).mapNotNull { row ->
            val provider = runCatching { RideProvider.valueOf(row.provider) }.getOrNull()
                ?: return@mapNotNull null
            TierRef(provider, row.serviceTier)
        }

    override suspend fun prune(cutoffMillis: Long) {
        dao.pruneOlderThan(cutoffMillis)
    }

    override fun observeRecentPricesByTier(
        sinceMillis: Long
    ): Flow<Map<TierRef, List<Double>>> =
        dao.observeRecentPrices(sinceMillis).map { rows ->
            // Group rows already arrive sorted oldest→newest from the DAO,
            // so the per-tier list preserves chronological order without a
            // second sort pass.
            rows.groupBy { it.provider to it.serviceTier }
                .mapNotNull { (key, rowList) ->
                    val provider = runCatching { RideProvider.valueOf(key.first) }.getOrNull()
                        ?: return@mapNotNull null
                    TierRef(provider, key.second) to rowList.map { it.priceInr }
                }
                .toMap()
        }

    private fun RideOption.toEntity(): PriceHistoryEntity = PriceHistoryEntity(
        provider = provider.name,
        serviceTier = serviceTier,
        priceInr = priceInr,
        basePriceInr = basePriceInr,
        surgeMultiplier = surgeMultiplier,
        fetchedAtMillis = fetchedAt.toEpochMilli()
    )
}
