package app.redlight.hanmadi.domain

import app.redlight.hanmadi.domain.model.Language
import app.redlight.hanmadi.domain.model.ListenEvent
import app.redlight.hanmadi.domain.model.Phrase
import app.redlight.hanmadi.domain.model.PracticeEvent
import app.redlight.hanmadi.domain.repository.SpeechRepository
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StopPracticeUseCaseTest {

    private class FakeSpeech(
        answer: String,
        private val events: Flow<ListenEvent> =
            flowOf(ListenEvent.Ready, ListenEvent.Volume(3.0), ListenEvent.Partial(answer.take(3)), ListenEvent.Final(answer)),
    ) : SpeechRepository {
        val spoken = mutableListOf<String>()
        override suspend fun speak(text: String, language: Language) {
            spoken += text
        }
        override fun listen(language: Language): Flow<ListenEvent> = events
        override fun stopAll() = Unit
    }

    private val phrase = Phrase(
        id = "m01",
        english = "Let's circle back to this later.",
        korean = "이건 나중에 다시 얘기해요.",
        keyChunk = "circle back",
        keyChunkKorean = "나중에 다시 다루다",
    )

    @Test
    fun `뜻과 문장을 들려주고 듣고 판정한 뒤 칭찬한다`() = runTest {
        val speech = FakeSpeech("let's circle back to this later")
        val events = StopPracticeUseCase(speech, pickPraise = { it.first() })(phrase, chunkOnly = false).toList()

        assertEquals(PracticeEvent.Prompting, events.first())
        val finished = events.last() as PracticeEvent.Finished
        assertTrue(finished.result.judgement.success)
        assertEquals(listOf(phrase.korean, phrase.english, "Nice!"), speech.spoken)
    }

    @Test
    fun `엔진이 준비된 뒤에야 말하라고 알린다`() = runTest {
        val notReady = flowOf<ListenEvent>(ListenEvent.Volume(1.0))
        val neverReady = StopPracticeUseCase(FakeSpeech("", notReady))(phrase, chunkOnly = false).toList()
        assertFalse(PracticeEvent.Listening in neverReady)

        val events = StopPracticeUseCase(FakeSpeech("circle back"))(phrase, chunkOnly = false).toList()
        val listening = events.indexOf(PracticeEvent.Listening)
        assertTrue(listening >= 0 && listening < events.indexOfFirst { it is PracticeEvent.Volume })
    }

    @Test
    fun `틀리면 정답 전체를 다시 들려준다`() = runTest {
        val speech = FakeSpeech("let's back")
        val finished = StopPracticeUseCase(speech, pickPraise = { it.first() })(phrase, chunkOnly = false).toList().last() as PracticeEvent.Finished

        assertFalse(finished.result.judgement.success)
        assertEquals(phrase.english, speech.spoken.last())
    }

    @Test
    fun `인식 엔진이 응답하지 않으면 제한 시간 뒤 들은 데까지로 판정하고 끝낸다`() = runTest {
        val stuck = flow<ListenEvent> {
            emit(ListenEvent.Partial("let's circle"))
            awaitCancellation()
        }
        val speech = FakeSpeech("", stuck)
        val finished = StopPracticeUseCase(speech, pickPraise = { it.first() })(phrase, chunkOnly = false).toList().last() as PracticeEvent.Finished

        assertEquals("let's circle", finished.result.heard)
        assertFalse(finished.result.judgement.success)
    }

    @Test
    fun `틀리면 무엇으로 들렸는지 말하고 정답을 들려준다`() = runTest {
        val speech = FakeSpeech("soccer back")
        StopPracticeUseCase(speech, pickPraise = { it.first() })(phrase, chunkOnly = true).toList()
        assertEquals(listOf("I heard soccer.", "circle back"), speech.spoken.takeLast(2))
    }

    @Test
    fun `아무것도 못 들으면 그렇게 말하고 정답을 들려준다`() = runTest {
        val speech = FakeSpeech("")
        StopPracticeUseCase(speech, pickPraise = { it.first() })(phrase, chunkOnly = true).toList()
        assertEquals(listOf("I didn't catch that.", "circle back"), speech.spoken.takeLast(2))
    }

    @Test
    fun `짧은 표현만 연습할 때는 표현과 그 뜻을 들려준다`() = runTest {
        val speech = FakeSpeech("circle back")
        val finished = StopPracticeUseCase(speech, pickPraise = { it.first() })(phrase, chunkOnly = true).toList().last() as PracticeEvent.Finished

        assertTrue(finished.result.judgement.success)
        assertEquals(listOf("나중에 다시 다루다", "circle back"), speech.spoken.take(2))
        assertTrue(finished.result.chunkOnly)
    }
}
