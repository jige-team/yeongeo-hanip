package app.redlight.hanmadi.domain.model

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

/** 운전 상태 판단에 쓰는 차량 신호 묶음 */
data class DriveSignal(
    val gear: Gear = Gear.UNKNOWN,
    val brakePressed: Boolean = false,
    /** null이면 속도를 모른다(속도 퍼미션이 없거나 아직 값이 안 옴) */
    val speedKmh: Float? = null,
    val driverDoorOpen: Boolean = false,
    /** 왼쪽이나 오른쪽 방향지시등이 켜져 있다 */
    val turnSignalOn: Boolean = false,
)

/**
 * 앱이 무엇을 해도 되는지를 기준으로 나눈 운전 상태.
 * 상세 기준: docs/design/drive-phase-rules.md
 */
enum class DrivePhase {
    /** 달리는 중이거나, 아직 섰다고 확정하지 못함. 듣기만 한다 */
    MOVING,

    /** 정차 중 브레이크를 뗐고 속도는 아직 0. 화면을 숨기고 새 문장은 시작하지 않는다 */
    ABOUT_TO_MOVE,

    /** 신호 대기·정체. 한 정차에 한 번 말하기 */
    STOPPED_SHORT,

    /** 2분 이상 정차 또는 N단. 하나씩 계속 말하기 */
    STOPPED_LONG,

    /** 서 있지만 방향지시등이 켜져 있다. 회전을 기다리는 중이라 말하기를 쉰다 */
    TURN_WAIT,

    /** P단. 화면으로 읽기 복습 */
    PARKED,

    /** P단에서 운전석 문이 열리거나 운전석이 빔. 세션 종료 */
    SESSION_END,
    ;

    val isStopped: Boolean get() = this == STOPPED_SHORT || this == STOPPED_LONG
}
