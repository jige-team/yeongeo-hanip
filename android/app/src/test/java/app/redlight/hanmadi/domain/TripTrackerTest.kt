package app.redlight.hanmadi.domain

import app.redlight.hanmadi.domain.TripTracker.Event
import app.redlight.hanmadi.domain.model.DrivePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripTrackerTest {

    private val trip = TripTracker(newTripAfterParkedMillis = 30 * 60_000L)

    @Test
    fun `앱을 켜고 처음 출발하면 운행 시작`() {
        assertNull(trip.onPhase(DrivePhase.PARKED, 0))
        assertEquals(Event.STARTED, trip.onPhase(DrivePhase.MOVING, 1_000))
    }

    @Test
    fun `내리지 않고 P단에 잠깐 섰다가 다시 출발하면 같은 운행`() {
        trip.onPhase(DrivePhase.MOVING, 0)
        assertNull(trip.onPhase(DrivePhase.PARKED, 60_000))
        assertNull(trip.onPhase(DrivePhase.MOVING, 120_000))
    }

    @Test
    fun `P단에서 운전석 문이 열리면 운행 끝, 다시 타고 출발하면 새 운행`() {
        trip.onPhase(DrivePhase.MOVING, 0)
        trip.onPhase(DrivePhase.PARKED, 60_000)
        assertEquals(Event.ENDED, trip.onPhase(DrivePhase.SESSION_END, 61_000))
        assertNull(trip.onPhase(DrivePhase.PARKED, 62_000)) // 문을 닫았다
        assertEquals(Event.STARTED, trip.onPhase(DrivePhase.MOVING, 70_000))
    }

    @Test
    fun `운행 끝은 한 번만 알린다`() {
        trip.onPhase(DrivePhase.MOVING, 0)
        assertEquals(Event.ENDED, trip.onPhase(DrivePhase.SESSION_END, 1_000))
        trip.onPhase(DrivePhase.PARKED, 2_000)
        assertNull(trip.onPhase(DrivePhase.SESSION_END, 3_000)) // 문을 다시 열었다 닫았다
    }

    @Test
    fun `내리지 않았어도 P단으로 30분 넘게 서 있었으면 새 운행`() {
        trip.onPhase(DrivePhase.MOVING, 0)
        trip.onPhase(DrivePhase.PARKED, 60_000)
        assertEquals(Event.STARTED, trip.onPhase(DrivePhase.MOVING, 60_000 + 30 * 60_000L))
    }

    @Test
    fun `정차와 회전 대기는 운행 경계가 아니다`() {
        trip.onPhase(DrivePhase.MOVING, 0)
        assertNull(trip.onPhase(DrivePhase.STOPPED_SHORT, 10_000))
        assertNull(trip.onPhase(DrivePhase.TURN_WAIT, 12_000))
        assertNull(trip.onPhase(DrivePhase.MOVING, 20_000))
    }
}
