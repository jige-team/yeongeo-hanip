package app.redlight.hanmadi.domain.repository

import app.redlight.hanmadi.domain.model.DriveSignal
import app.redlight.hanmadi.domain.model.Language
import app.redlight.hanmadi.domain.model.ListenEvent
import app.redlight.hanmadi.domain.model.Phrase
import app.redlight.hanmadi.domain.model.PhraseSet
import app.redlight.hanmadi.domain.model.PracticeResult
import app.redlight.hanmadi.domain.model.Streak
import kotlinx.coroutines.flow.Flow

interface VehicleRepository {
    /** 기어·브레이크·속도·문·방향지시등을 하나로 묶어 바뀔 때마다 내보낸다 */
    fun driveSignal(): Flow<DriveSignal>
}

interface SpeechRepository {
    /** 다 읽을 때까지 기다린다. 코루틴이 취소되면 재생도 멈춘다 */
    suspend fun speak(text: String, language: Language)

    /** 한 번 말하면 끝나는 음성 인식. Final이나 Error가 오면 Flow가 끝난다 */
    fun listen(language: Language): Flow<ListenEvent>

    /** 재생과 인식을 모두 멈춘다 */
    fun stopAll()
}

/** 앱이 말하거나 듣는 동안 다른 앱의 소리(음악)를 어떻게 할지 */
enum class FocusMode {
    /** 소리만 줄인다. 주행 중 미리 듣기처럼 짧게 들려줄 때 (내비 안내와 같은 방식) */
    DUCK,
    /** 잠깐 멈춘다. 정차 중 말하기처럼 마이크로 들어야 할 때 */
    PAUSE,
}

interface AudioFocusRepository {
    /** 다른 소리를 [mode]대로 비켜 달라고 요청한다. 이미 잡고 있으면 바꾼다 */
    fun acquire(mode: FocusMode)

    /** 돌려준다. 멈췄던 음악이 다시 나온다 */
    fun release()
}

/** 효과음. 말할 차례, 맞음, 틀림을 소리로 바로 알린다(운전자가 화면을 보지 않아도 되게) */
enum class SoundEffect {
    /** 마이크가 준비됐다. 지금 말하면 된다 */
    YOUR_TURN,
    /** 맞혔다. 들을 때마다 조금씩 다른 소리가 난다 */
    SUCCESS,
    /** 3번 이상 연속으로 맞혔다 */
    COMBO,
    /** 틀렸다. 한 번 더 한다 */
    RETRY,
    /** 기회를 다 썼다. 다음 표현으로 넘어간다 */
    MOVE_ON,
}

interface SoundRepository {
    /** 다 울릴 때까지 기다린다 */
    suspend fun play(effect: SoundEffect)
}

interface PhraseRepository {
    /** [set] 중에서 오늘 연습할 표현. 날마다 바뀐다 */
    suspend fun todaysPhrases(set: PhraseSet): List<Phrase>
}

interface ProgressRepository {
    /** 오늘 결과를 기록하고 갱신된 연속 기록을 돌려준다 */
    suspend fun record(results: List<PracticeResult>, todayEpochDay: Long): Streak
    suspend fun streak(): Streak
}
