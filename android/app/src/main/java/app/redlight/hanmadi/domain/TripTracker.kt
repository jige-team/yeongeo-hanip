package app.redlight.hanmadi.domain

import app.redlight.hanmadi.domain.model.DrivePhase

/**
 * 운행의 시작과 끝을 가른다. P단만으로는 "도착"과 "잠깐 정차"를 구분할 수 없어서
 * 운전석 문으로 판단한 SESSION_END(내림)를 끝으로 본다.
 *
 * - 끝: 운행 중에 SESSION_END(P단 + 운전석 문 열림)가 됐다
 * - 시작: 운행 중이 아닐 때 차가 움직였다 (앱을 켠 뒤 첫 출발, 내렸다가 다시 타고 출발)
 * - 내리지 않고 P단에 잠깐 섰다가 다시 출발하면 같은 운행이다 (편의점, 드라이브스루)
 * - 내리지 않았어도 P단으로 [newTripAfterParkedMillis] 이상 서 있었으면 새 운행으로 본다
 *   (문을 열지 않고 차 안에서 오래 쉬다 출발하는 경우) [판단]
 */
class TripTracker(private val newTripAfterParkedMillis: Long = 30 * 60_000L) {

    enum class Event { STARTED, ENDED }

    private var onTrip = false
    private var parkedSince: Long? = null

    /** 운전 상태가 바뀔 때마다 부른다. 운행이 시작되거나 끝난 순간에만 이벤트를 돌려준다 */
    fun onPhase(phase: DrivePhase, nowMillis: Long): Event? = when (phase) {
        DrivePhase.SESSION_END -> {
            parkedSince = parkedSince ?: nowMillis
            if (onTrip) {
                onTrip = false
                Event.ENDED
            } else {
                null
            }
        }
        DrivePhase.PARKED -> {
            parkedSince = parkedSince ?: nowMillis
            null
        }
        DrivePhase.MOVING -> {
            val parkedLong = parkedSince?.let { nowMillis - it >= newTripAfterParkedMillis } ?: false
            parkedSince = null
            if (!onTrip || parkedLong) {
                onTrip = true
                Event.STARTED
            } else {
                null
            }
        }
        // 정차·회전 대기·출발 직전은 운행 중의 일이다
        else -> null
    }
}
