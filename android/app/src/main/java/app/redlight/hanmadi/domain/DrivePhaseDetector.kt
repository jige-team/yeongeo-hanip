package app.redlight.hanmadi.domain

import app.redlight.hanmadi.domain.model.DrivePhase
import app.redlight.hanmadi.domain.model.DriveSignal
import app.redlight.hanmadi.domain.model.Gear

/**
 * 차량 신호로 운전 상태를 판단한다. 기준: docs/design/drive-phase-rules.md
 *
 * 원칙: 판단이 애매하면 MOVING이다. 정차를 놓치면 한 문장을 놓칠 뿐이지만,
 * 주행을 정차로 착각하면 안전 문제가 된다.
 */
class DrivePhaseDetector(private val config: Config = Config()) {

    data class Config(
        /** 이만큼 서 있어야 정차로 확정한다 */
        val holdMillis: Long = 2_000,
        /** 정체 중에는 기준을 늘린다 */
        val jamHoldMillis: Long = 4_000,
        /** 이보다 짧은 정차가 연속 3번이면 정체로 본다 */
        val jamStopMillis: Long = 5_000,
        /** 이보다 긴 정차가 한 번 나오면 정체 판단을 초기화한다 */
        val jamResetMillis: Long = 30_000,
        /** 이보다 오래 서 있으면 잠깐 세움으로 본다 */
        val longStopMillis: Long = 120_000,
        /** 브레이크를 뗀 뒤 이만큼 속도가 0이면 오토홀드로 본다 */
        val autoHoldGraceMillis: Long = 1_500,
        /** 이보다 느리면 서 있는 것으로 본다 */
        val standstillKmh: Float = 0.5f,
    )

    private var standingSince: Long? = null
    private var brakeReleasedAt: Long? = null
    /** P단에서 나온 뒤 한 번이라도 움직였는지. 출발 전에 서 있는 것은 신호 대기가 아니다 */
    private var departed = false
    private val recentStops = ArrayDeque<Long>()

    /** 최근 정차가 연속으로 짧으면 정체다. 정체 중에는 짧은 표현만 연습한다 */
    val isJam: Boolean
        get() = recentStops.size == JAM_COUNT && recentStops.all { it < config.jamStopMillis }

    fun update(signal: DriveSignal, nowMillis: Long): DrivePhase {
        if (signal.gear == Gear.PARK) {
            endStop(nowMillis)
            departed = false
            // 내렸다 = 운전석 문이 열렸다. 착석 신호는 에뮬레이터 콘솔과 SDK 값이 반대로 들어와(2026-11-01) 쓰지 않는다
            val leaving = signal.driverDoorOpen
            return if (leaving) DrivePhase.SESSION_END else DrivePhase.PARKED
        }

        // 속도를 모르면 브레이크만 믿는다. 브레이크를 떼면 바로 움직이는 것으로 본다
        val moving = signal.speedKmh?.let { it >= config.standstillKmh } ?: !signal.brakePressed
        if (moving) {
            endStop(nowMillis)
            departed = true
            return DrivePhase.MOVING
        }
        if (!departed) return DrivePhase.MOVING

        val since = standingSince ?: nowMillis.also { standingSince = it }
        if (signal.brakePressed) brakeReleasedAt = null
        else if (brakeReleasedAt == null) brakeReleasedAt = nowMillis

        // 정차 확정 전에는 MOVING이다. 확정 전에 다른 상태를 끼우면 주행과 정차가 깜빡인다
        val stoppedFor = nowMillis - since
        val hold = if (isJam) config.jamHoldMillis else config.holdMillis
        if (stoppedFor < hold) return DrivePhase.MOVING

        // 속도는 0인데 브레이크를 뗐다: 출발 직전이거나 오토홀드다.
        // 유예 시간이 지나도 서 있으면 오토홀드로 보고 정차를 이어 간다
        val releasedAt = brakeReleasedAt
        if (releasedAt != null && nowMillis - releasedAt < config.autoHoldGraceMillis) return DrivePhase.ABOUT_TO_MOVE

        // 회전을 기다릴 때는 맞은편 차와 보행자를 봐야 한다. 서 있어도 말하기를 쉰다
        if (signal.turnSignalOn) return DrivePhase.TURN_WAIT

        return when {
            signal.gear == Gear.NEUTRAL || stoppedFor >= config.longStopMillis -> DrivePhase.STOPPED_LONG
            else -> DrivePhase.STOPPED_SHORT
        }
    }

    private fun endStop(nowMillis: Long) {
        standingSince?.let { record(nowMillis - it) }
        standingSince = null
        brakeReleasedAt = null
    }

    private fun record(stopMillis: Long) {
        if (stopMillis >= config.jamResetMillis) {
            recentStops.clear()
            return
        }
        recentStops.addLast(stopMillis)
        if (recentStops.size > JAM_COUNT) recentStops.removeFirst()
    }

    private companion object {
        const val JAM_COUNT = 3
    }
}
