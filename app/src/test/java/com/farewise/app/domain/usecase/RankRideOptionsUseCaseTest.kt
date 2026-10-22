package com.farewise.app.domain.usecase

import com.farewise.app.domain.model.RideRankingCategory
import com.farewise.app.testing.Fixtures.ride
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RankRideOptionsUseCaseTest {

    private val rank = RankRideOptionsUseCase()

    private val cheapButSlow = ride("bike", priceInr = 90.0, etaMinutes = 12, tripMinutes = 40)
    private val pricyButFast = ride("premier", priceInr = 420.0, etaMinutes = 2, tripMinutes = 18)
    private val balanced = ride("go", priceInr = 180.0, etaMinutes = 4, tripMinutes = 22)
    private val all = listOf(pricyButFast, cheapButSlow, balanced)

    @Test
    fun `empty input returns empty list`() {
        RideRankingCategory.entries.forEach { category ->
            assertTrue(rank(emptyList(), category).isEmpty())
        }
    }

    @Test
    fun `cheapest sorts by price ascending`() {
        val ids = rank(all, RideRankingCategory.CHEAPEST).map { it.id }
        assertEquals(listOf("bike", "go", "premier"), ids)
    }

    @Test
    fun `fastest sorts by pickup eta plus trip time`() {
        val ids = rank(all, RideRankingCategory.FASTEST).map { it.id }
        assertEquals(listOf("premier", "go", "bike"), ids)
    }

    @Test
    fun `recommended prefers the balanced option over either extreme`() {
        val top = rank(all, RideRankingCategory.RECOMMENDED).first()
        assertEquals("go", top.id)
    }

    @Test
    fun `recommended penalises surge when price and time are equal`() {
        val calm = ride("calm", priceInr = 200.0, surgeMultiplier = 1.0)
        val surging = ride("surging", priceInr = 200.0, surgeMultiplier = 2.0)
        val ids = rank(listOf(surging, calm), RideRankingCategory.RECOMMENDED).map { it.id }
        assertEquals(listOf("calm", "surging"), ids)
    }

    @Test
    fun `ranking keeps every option`() {
        RideRankingCategory.entries.forEach { category ->
            assertEquals(all.toSet(), rank(all, category).toSet())
        }
    }
}
