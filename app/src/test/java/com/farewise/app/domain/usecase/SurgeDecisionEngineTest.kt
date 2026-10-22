package com.farewise.app.domain.usecase

import com.farewise.app.domain.model.RideAdvice
import com.farewise.app.domain.model.RideProvider
import com.farewise.app.testing.Fixtures.prediction
import com.farewise.app.testing.Fixtures.ride
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration

class SurgeDecisionEngineTest {

    private val engine = SurgeDecisionEngine()

    private val uberGo = ride("go", priceInr = 200.0, provider = RideProvider.UBER, serviceTier = "Go")

    @Test
    fun `no options or no predictions gives no advice`() {
        val p = prediction(RideProvider.UBER, "Go", listOf(200.0))
        assertNull(engine(emptyList(), listOf(p)))
        assertNull(engine(listOf(uberGo), emptyList()))
    }

    @Test
    fun `big enough drop after a real wait suggests waiting`() {
        // 200 -> 170 at step 2 (10 min): 15% drop, wait >= 5 min.
        val p = prediction(RideProvider.UBER, "Go", listOf(200.0, 190.0, 170.0, 185.0))
        val advice = requireNotNull(engine(listOf(uberGo), listOf(p))) { "expected advice" }

        assertEquals(RideAdvice.Kind.WAIT_FOR_DROP, advice.kind)
        assertEquals(170.0, advice.targetPriceInr, 0.0)
        assertEquals(Duration.ofMinutes(10), advice.waitFor)
    }

    @Test
    fun `drop that arrives too soon is not worth waiting for`() {
        // Cheapest point is the very first step (0 min wait).
        val p = prediction(RideProvider.UBER, "Go", listOf(150.0, 200.0, 200.0))
        assertNull(engine(listOf(uberGo), listOf(p)))
    }

    @Test
    fun `rising forecast suggests booking now`() {
        val p = prediction(RideProvider.UBER, "Go", listOf(200.0, 210.0, 230.0))
        val advice = requireNotNull(engine(listOf(uberGo), listOf(p))) { "expected advice" }

        assertEquals(RideAdvice.Kind.BOOK_NOW, advice.kind)
        assertEquals(Duration.ZERO, advice.waitFor)
    }

    @Test
    fun `small wobble is ignored`() {
        val p = prediction(RideProvider.UBER, "Go", listOf(200.0, 196.0, 204.0))
        assertNull(engine(listOf(uberGo), listOf(p)))
    }

    @Test
    fun `low confidence predictions are ignored`() {
        val p = prediction(RideProvider.UBER, "Go", listOf(200.0, 150.0, 140.0), confidence = 0.2f)
        assertNull(engine(listOf(uberGo), listOf(p)))
    }

    @Test
    fun `picks the candidate with the biggest rupee swing`() {
        val bike = ride("bike", priceInr = 100.0, provider = RideProvider.RAPIDO, serviceTier = "Bike")
        val cab = ride("cab", priceInr = 400.0, provider = RideProvider.OLA, serviceTier = "Prime")
        val predictions = listOf(
            // Bike: 20% drop = ₹20.
            prediction(RideProvider.RAPIDO, "Bike", listOf(100.0, 90.0, 80.0)),
            // Cab: 10% drop = ₹40 — smaller percentage, bigger saving.
            prediction(RideProvider.OLA, "Prime", listOf(400.0, 380.0, 360.0))
        )

        val advice = requireNotNull(engine(listOf(bike, cab), predictions)) { "expected advice" }
        assertEquals(RideProvider.OLA, advice.provider)
    }
}
