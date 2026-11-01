package app.redlight.hanmadi.data

import app.redlight.hanmadi.domain.model.DriveSignal
import app.redlight.hanmadi.domain.model.Language
import app.redlight.hanmadi.domain.model.ListenEvent
import app.redlight.hanmadi.domain.repository.SpeechRepository
import app.redlight.hanmadi.domain.repository.VehicleRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update

/**
 * Pleos 에뮬레이터가 아닌 기기(일반 폰·태블릿 에뮬레이터)에서 화면과 흐름을 개발하기 위한 가짜 구현.
 * 8GB Mac에서 Pleos 에뮬레이터를 계속 켜 둘 수 없어서 둔다. 화면의 디버그 패널로 조작한다.
 */
class FakeVehicleRepository : VehicleRepository {
    val signal = MutableStateFlow(DriveSignal())
    override fun driveSignal(): Flow<DriveSignal> = signal
    fun set(transform: DriveSignal.() -> DriveSignal) = signal.update(transform)
}

/** 말하는 척만 한다. 듣기는 3초 뒤 빈 답으로 끝난다 */
class FakeSpeechRepository : SpeechRepository {
    override suspend fun speak(text: String, language: Language) = delay(text.length * 50L)
    override fun listen(language: Language): Flow<ListenEvent> = flow {
        emit(ListenEvent.Ready)
        repeat(6) {
            emit(ListenEvent.Volume(it * 1.5))
            delay(500)
        }
        emit(ListenEvent.Final(""))
    }
    override fun stopAll() = Unit
}
