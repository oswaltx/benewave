package dev.greenwave.shared.guidance

import kotlin.test.Test
import kotlin.test.assertEquals

class SpeedAdvisorTest {

    @Test
    fun noTargetMeansNoAdvice() {
        assertEquals(SpeedAdvice.NONE, SpeedAdvisor.advise(currentSpeedKmh = 18.0, targetSpeedKmh = null))
    }

    @Test
    fun withinToleranceMeansHold() {
        assertEquals(SpeedAdvice.HOLD, SpeedAdvisor.advise(18.0, 20.0))
        assertEquals(SpeedAdvice.HOLD, SpeedAdvisor.advise(22.0, 20.0))
        assertEquals(SpeedAdvice.HOLD, SpeedAdvisor.advise(20.0, 20.0))
    }

    @Test
    fun tooSlowMeansFaster() {
        assertEquals(SpeedAdvice.FASTER, SpeedAdvisor.advise(17.9, 20.0))
        assertEquals(SpeedAdvice.FASTER, SpeedAdvisor.advise(0.0, 12.0))
    }

    @Test
    fun tooFastMeansSlower() {
        assertEquals(SpeedAdvice.SLOWER, SpeedAdvisor.advise(22.1, 20.0))
    }

    @Test
    fun customToleranceIsRespected() {
        assertEquals(SpeedAdvice.HOLD, SpeedAdvisor.advise(15.0, 20.0, toleranceKmh = 5.0))
        assertEquals(SpeedAdvice.FASTER, SpeedAdvisor.advise(15.0, 20.0, toleranceKmh = 1.0))
    }
}
