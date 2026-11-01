package app.redlight.hanmadi.presentation

import app.redlight.hanmadi.domain.model.DrivePhase
import app.redlight.hanmadi.domain.model.DriveSignal
import app.redlight.hanmadi.domain.model.Judgement
import app.redlight.hanmadi.domain.model.Phrase
import app.redlight.hanmadi.domain.model.PhraseSet
import app.redlight.hanmadi.domain.model.PracticeResult
import app.redlight.hanmadi.domain.model.Streak

data class DriveState(
    val phase: DrivePhase = DrivePhase.PARKED,
    val isJam: Boolean = false,
    /** 지금 연습하는 세트(업무 영어 / 일상 영어) */
    val phraseSet: PhraseSet = PhraseSet.WORK,
    val phrases: List<Phrase> = emptyList(),
    val currentIndex: Int = 0,
    val stop: StopUi = StopUi(),
    /** 오늘 정차 중에 말한 결과 (말한 순서) */
    val results: List<PracticeResult> = emptyList(),
    /** 주차 후 다시 읽기 결과 (문장 id별) */
    val reviewJudgements: Map<String, Judgement> = emptyMap(),
    val reviewingId: String? = null,
    val streak: Streak = Streak(0, 0),
    /** 가짜 차량일 때만 디버그 패널을 보여 준다 */
    val debugSignal: DriveSignal? = null,
    /** 측정 모드 진행 표시 (예: "측정 3/20"). null이면 측정 중이 아니다 */
    val measureLabel: String? = null,
) {
    val current: Phrase? get() = phrases.getOrNull(currentIndex)
    val successCount: Int get() = results.count { it.judgement.success }
    /** 지금까지 연속으로 맞힌 표현 수. 3 이상이면 화면과 효과음으로 따로 칭찬한다 */
    val combo: Int get() = results.takeLastWhile { it.judgement.success }.size
    /** 오늘 연습한 표현 중 틀린 것 (결과 카드의 "어려웠던 표현") */
    val hardPhrases: List<Phrase> get() = results.filterNot { it.judgement.success }.map { it.phrase }

    /** 표현 하나의 오늘 상태: 성공, 실패, 아직 안 함 */
    fun statusOf(phrase: Phrase): Boolean? =
        reviewJudgements[phrase.id]?.success ?: results.lastOrNull { it.phrase.id == phrase.id }?.judgement?.success
}

/** 정차 중 말하기 화면 상태 */
data class StopUi(
    val step: Step = Step.IDLE,
    /** 지금 말하는 표현. 판정 뒤 다음 표현으로 넘어가도 결과를 보여 주는 동안은 이 표현을 띄운다 */
    val phrase: Phrase? = null,
    val rms: Double = 0.0,
    val lastJudgement: Judgement? = null,
    /** 이 표현을 몇 번째 말하는지 (1부터) */
    val attempt: Int = 1,
    val maxAttempts: Int = 1,
    /** 틀렸고, 곧 같은 표현을 다시 말한다 */
    val willRetry: Boolean = false,
) {
    enum class Step { IDLE, PROMPTING, LISTENING, DONE }
}

sealed interface DriveIntent {
    data object Start : DriveIntent
    data object PermissionsHandled : DriveIntent
    /** 주차 상태에서 문장 하나를 보면서 다시 읽기 */
    data class ReadAloud(val phraseId: String) : DriveIntent
    /** 가짜 차량 조작 (디버그 패널) */
    data class Debug(val change: DriveSignal.() -> DriveSignal) : DriveIntent
    /** 인식률 측정: 주차 상태에서 표현 20개를 연달아 말한다 (디버그 빌드 전용, tools/measure_stt.sh) */
    data object StartMeasure : DriveIntent
}

sealed interface DriveSideEffect {
    data class RequestPermissions(val permissions: List<String>) : DriveSideEffect
}
