package app.redlight.hanmadi.domain.model

enum class Language { ENGLISH, KOREAN }

/**
 * 표현 세트. 출근길엔 업무 영어, 퇴근길엔 일상 영어를 연습한다.
 * 운전자가 고르지 않아도 되도록 시간으로 정한다 [판단: 출퇴근 시간대 기준]
 */
enum class PhraseSet(val label: String) {
    WORK("업무 영어"),
    DAILY("일상 영어"),
    ;

    companion object {
        /** 이 시각부터는 퇴근길로 본다 */
        const val EVENING_FROM_HOUR = 14

        fun forHour(hour: Int): PhraseSet = if (hour < EVENING_FROM_HOUR) WORK else DAILY
    }
}

/** 연습할 문장 하나 */
data class Phrase(
    val id: String,
    val english: String,
    val korean: String,
    /** 정차 중에 듣고 따라 말하는 핵심 표현 (1~4단어). 문장 전체는 주차 중에만 읽는다 */
    val keyChunk: String,
    /** 핵심 표현의 뜻. 정차 화면에서 표현 아래에 작게 보여 준다 */
    val keyChunkKorean: String,
    val set: PhraseSet = PhraseSet.WORK,
)

/** 말한 내용을 목표와 비교한 결과 */
data class Judgement(
    val success: Boolean,
    /** 목표에는 있는데 들리지 않은 단어들 (원래 순서) */
    val missingWords: List<String>,
    /** 놓친 핵심 단어와 그 자리에 대신 들린 단어. ("meeting", "meaning") = meeting이 meaning으로 들렸다 */
    val misheard: List<Pair<String, String>> = emptyList(),
)

data class PracticeResult(
    val phrase: Phrase,
    /** 정체 때처럼 핵심 표현만 연습했는지 */
    val chunkOnly: Boolean,
    val heard: String,
    val judgement: Judgement,
)

/** 음성 인식 진행 상황 */
sealed interface ListenEvent {
    /** 인식 엔진이 들을 준비가 됐다. 이때부터 말해야 인식된다 (요청 뒤 0.5~4.4초) */
    data object Ready : ListenEvent
    data class Volume(val rms: Double) : ListenEvent
    data class Partial(val text: String) : ListenEvent
    data class Final(val text: String) : ListenEvent
    data class Error(val reason: String) : ListenEvent
}

/** 정차 중 한 번의 말하기 연습이 진행되는 단계 */
sealed interface PracticeEvent {
    data object Prompting : PracticeEvent
    data object Listening : PracticeEvent
    data class Volume(val rms: Double) : PracticeEvent
    data class Heard(val text: String) : PracticeEvent
    /** 판정이 나온 순간. 피드백 음성을 읽기 전에 화면과 효과음을 먼저 바꾼다 */
    data class Judged(val target: String, val judgement: Judgement) : PracticeEvent
    data class Finished(val result: PracticeResult) : PracticeEvent
}

data class Streak(val days: Int, val totalSuccess: Int)
