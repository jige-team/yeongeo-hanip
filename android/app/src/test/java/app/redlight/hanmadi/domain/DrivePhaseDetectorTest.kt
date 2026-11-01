package app.redlight.hanmadi.domain

import app.redlight.hanmadi.domain.model.DrivePhase
import app.redlight.hanmadi.domain.model.DriveSignal
import app.redlight.hanmadi.domain.model.Gear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivePhaseDetectorTest {

    private val driving = DriveSignal(Gear.DRIVE, brakePressed = false, speedKmh = 40f)
    /** 출발한 뒤부터 시작한다. 출발 전 정차는 따로 시험한다 */
    private val detector = DrivePhaseDetector().apply { update(driving, 0) }
    private val braking = DriveSignal(Gear.DRIVE, brakePressed = true, speedKmh = 0f)
    private val autoHold = DriveSignal(Gear.DRIVE, brakePressed = false, speedKmh = 0f)

    /** 정차 한 번을 흉내 낸다: [from]부터 [millis] 동안 서 있다가 출발 */
    private fun stopFor(from: Long, millis: Long): Long {
        detector.update(braking, from)
        detector.update(braking, from + millis)
        detector.update(driving, from + millis + 1)
        return from + millis + 1
    }

    @Test
    fun `P단은 PARKED`() {
        assertEquals(DrivePhase.PARKED, detector.update(DriveSignal(Gear.PARK), 0))
    }

    @Test
    fun `P단에서 운전석 문이 열리면 SESSION_END`() {
        assertEquals(DrivePhase.SESSION_END, detector.update(DriveSignal(Gear.PARK, driverDoorOpen = true), 0))
    }

    @Test
    fun `달리는 중이면 MOVING`() {
        assertEquals(DrivePhase.MOVING, detector.update(driving, 0))
    }

    @Test
    fun `브레이크를 2초 이상 밟고 서 있어야 STOPPED_SHORT`() {
        assertEquals(DrivePhase.MOVING, detector.update(braking, 0))
        assertEquals(DrivePhase.MOVING, detector.update(braking, 1_999))
        assertEquals(DrivePhase.STOPPED_SHORT, detector.update(braking, 2_000))
    }

    @Test
    fun `속도가 붙으면 브레이크를 밟고 있어도 MOVING`() {
        detector.update(braking, 0)
        detector.update(braking, 3_000)
        assertEquals(DrivePhase.MOVING, detector.update(braking.copy(speedKmh = 3f), 3_100))
    }

    @Test
    fun `정차 중 브레이크를 떼면 먼저 ABOUT_TO_MOVE`() {
        detector.update(braking, 0)
        detector.update(braking, 3_000)
        assertEquals(DrivePhase.ABOUT_TO_MOVE, detector.update(autoHold, 3_100))
    }

    @Test
    fun `브레이크를 떼고 1_5초가 지나도 서 있으면 오토홀드로 보고 정차 유지`() {
        detector.update(braking, 0)
        detector.update(braking, 3_000)
        detector.update(autoHold, 3_100)
        assertEquals(DrivePhase.ABOUT_TO_MOVE, detector.update(autoHold, 4_599))
        assertEquals(DrivePhase.STOPPED_SHORT, detector.update(autoHold, 4_600))
    }

    @Test
    fun `P단에서 D단으로 바꾸고 출발 전에 서 있는 것은 정차가 아니다`() {
        detector.update(DriveSignal(Gear.PARK), 0)
        assertEquals(DrivePhase.MOVING, detector.update(braking, 100))
        assertEquals(DrivePhase.MOVING, detector.update(braking, 10_000))
        detector.update(driving, 11_000)
        detector.update(braking, 12_000)
        assertEquals(DrivePhase.STOPPED_SHORT, detector.update(braking, 14_000))
    }

    @Test
    fun `브레이크 없이 서서히 멈추면 정차 확정 전까지 MOVING만 나온다`() {
        val phases = (0L..2_000L step 250).map { detector.update(autoHold, it) }
        assertTrue(phases.dropLast(1).all { it == DrivePhase.MOVING })
        assertEquals(DrivePhase.STOPPED_SHORT, phases.last())
    }

    @Test
    fun `오토홀드 중 속도가 붙으면 즉시 MOVING`() {
        detector.update(braking, 0)
        detector.update(braking, 3_000)
        detector.update(autoHold, 5_000)
        detector.update(autoHold, 7_000)
        assertEquals(DrivePhase.MOVING, detector.update(autoHold.copy(speedKmh = 2f), 7_100))
    }

    @Test
    fun `속도를 모르면 브레이크를 떼는 순간 MOVING`() {
        val unknownSpeed = braking.copy(speedKmh = null)
        detector.update(unknownSpeed, 0)
        assertEquals(DrivePhase.STOPPED_SHORT, detector.update(unknownSpeed, 2_000))
        assertEquals(DrivePhase.MOVING, detector.update(unknownSpeed.copy(brakePressed = false), 2_100))
    }

    @Test
    fun `서 있는데 방향지시등이 켜져 있으면 회전 대기라서 말하기를 쉰다`() {
        detector.update(braking, 0)
        assertEquals(DrivePhase.TURN_WAIT, detector.update(braking.copy(turnSignalOn = true), 2_000))
        // 깜빡이를 끄고 계속 서 있으면 바로 정차로 돌아온다(기준 시간을 다시 기다리지 않는다)
        assertEquals(DrivePhase.STOPPED_SHORT, detector.update(braking, 2_500))
    }

    @Test
    fun `달리는 중에는 방향지시등과 상관없이 MOVING`() {
        assertEquals(DrivePhase.MOVING, detector.update(driving.copy(turnSignalOn = true), 1_000))
    }

    @Test
    fun `2분 이상 서 있으면 STOPPED_LONG`() {
        detector.update(braking, 0)
        assertEquals(DrivePhase.STOPPED_SHORT, detector.update(braking, 119_999))
        assertEquals(DrivePhase.STOPPED_LONG, detector.update(braking, 120_000))
    }

    @Test
    fun `N단으로 서 있으면 STOPPED_LONG`() {
        val neutral = braking.copy(gear = Gear.NEUTRAL)
        detector.update(neutral, 0)
        assertEquals(DrivePhase.STOPPED_LONG, detector.update(neutral, 2_000))
    }

    @Test
    fun `5초 미만 정차가 3번 이어지면 정체로 보고 기준을 4초로 늘린다`() {
        var t = 0L
        repeat(3) { t = stopFor(t, 3_000) + 10_000 }
        assertTrue(detector.isJam)

        detector.update(braking, t)
        assertEquals(DrivePhase.MOVING, detector.update(braking, t + 3_000))
        assertEquals(DrivePhase.STOPPED_SHORT, detector.update(braking, t + 4_000))
    }

    @Test
    fun `30초 이상 정차가 한 번 나오면 정체 판단을 초기화한다`() {
        var t = 0L
        repeat(3) { t = stopFor(t, 3_000) + 10_000 }
        assertTrue(detector.isJam)

        stopFor(t, 40_000)
        assertFalse(detector.isJam)
    }
}
