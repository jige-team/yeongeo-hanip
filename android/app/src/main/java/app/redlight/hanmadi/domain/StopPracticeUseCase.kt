package app.redlight.hanmadi.domain

import app.redlight.hanmadi.domain.model.Judgement
import app.redlight.hanmadi.domain.model.Language
import app.redlight.hanmadi.domain.model.ListenEvent
import app.redlight.hanmadi.domain.model.Phrase
import app.redlight.hanmadi.domain.model.PracticeEvent
import app.redlight.hanmadi.domain.model.PracticeResult
import app.redlight.hanmadi.domain.repository.SpeechRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 정차 중 한 번의 말하기 연습: 뜻 → 영어 들려주기 → 듣기 → 판정 → 짧은 피드백.
 *
 * 차가 출발하면 호출한 쪽이 이 Flow 수집을 취소한다. 그러면 재생과 인식도 함께 멈춘다.
 */
class StopPracticeUseCase(
    private val speech: SpeechRepository,
    /** 칭찬을 고른다. 매번 같은 말이면 금방 질리므로 기본은 무작위다. 테스트에서는 고정한다 */
    private val pickPraise: (List<String>) -> String = { it.random() },
) {

    /** 항상 짧은 표현만 말한다. 문장 전체를 보여 주면 문장을 말해야 하는 것처럼 보였다(2026-11-01 사용자 결정) */
    operator fun invoke(phrase: Phrase): Flow<PracticeEvent> = flow {
        val target = phrase.keyChunk
        val meaning = phrase.keyChunkKorean

        // 뜻을 먼저 듣고 영어를 들으면, 무슨 말을 하려는지 알고 따라 할 수 있다
        emit(PracticeEvent.Prompting)
        speech.speak(meaning, Language.KOREAN)
        speech.speak(target, Language.ENGLISH)

        var heard = ""
        // 인식 엔진이 응답하지 않아도 정차 시간 안에 끝나도록 듣는 시간을 제한한다.
        // 시간이 다 되면 그때까지 들은 말로 판정한다
        withTimeoutOrNull(LISTEN_LIMIT_MILLIS) {
            speech.listen(Language.ENGLISH).collect { event ->
                when (event) {
                    // "지금 말하세요"는 엔진이 준비된 뒤에 띄운다. 먼저 띄우면 앞부분이 잘린다 (에뮬레이터 실측 3초 차이)
                    ListenEvent.Ready -> emit(PracticeEvent.Listening)
                    is ListenEvent.Volume -> emit(PracticeEvent.Volume(event.rms))
                    is ListenEvent.Partial -> {
                        heard = event.text
                        emit(PracticeEvent.Heard(event.text))
                    }
                    is ListenEvent.Final -> {
                        heard = event.text
                        emit(PracticeEvent.Heard(event.text))
                    }
                    is ListenEvent.Error -> Unit // 못 들었으면 빈 답으로 판정한다
                }
            }
        }

        val judgement = AnswerJudge.judge(target, heard)
        emit(PracticeEvent.Judged(target, judgement))
        if (judgement.success) {
            speech.speak(pickPraise(PRAISES), Language.ENGLISH)
        } else {
            // 어떻게 들렸는지 짚은 뒤 정답 전체를 원래 순서대로 들려준다.
            // 놓친 단어만 떼어 들려주면 "deadline the"처럼 정답과 다른 말로 들렸다(2026-11-01 사용자 지적)
            speech.speak(missFeedback(heard, judgement), Language.ENGLISH)
            speech.speak(target, Language.ENGLISH)
        }

        emit(PracticeEvent.Finished(PracticeResult(phrase, heard, judgement)))
    }

    /** 틀렸을 때 한마디: 못 알아들었는지, 무엇으로 들렸는지 */
    private fun missFeedback(heard: String, judgement: Judgement): String {
        val (_, heardWord) = judgement.misheard.firstOrNull() ?: return if (heard.isBlank()) "I didn't catch that." else "Almost."
        return "I heard $heardWord."
    }

    companion object {
        val PRAISES = listOf("Nice!", "Great!", "Perfect!", "Well done!", "Excellent!")

        /**
         * 인식 준비(스파이크 실측 최대 4.4초) 뒤에도 10초 넘게 말할 수 있게 한다 [판단].
         * 12초일 때 에뮬레이터에서 소리가 1~3초 늦게 들어와 말하는 도중에 끊긴 일이 있었다(2026-11-01).
         */
        const val LISTEN_LIMIT_MILLIS = 16_000L
    }
}
