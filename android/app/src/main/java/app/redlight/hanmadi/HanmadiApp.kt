package app.redlight.hanmadi

import app.redlight.hanmadi.data.AndroidAudioFocusRepository
import app.redlight.hanmadi.data.AssetPhraseRepository
import app.redlight.hanmadi.data.FakeSpeechRepository
import app.redlight.hanmadi.data.FakeVehicleRepository
import app.redlight.hanmadi.data.PleosSpeechRepository
import app.redlight.hanmadi.data.PleosVehicleRepository
import app.redlight.hanmadi.data.PrefsProgressRepository
import app.redlight.hanmadi.data.ToneSoundRepository
import app.redlight.hanmadi.domain.StopPracticeUseCase
import app.redlight.hanmadi.domain.repository.AudioFocusRepository
import app.redlight.hanmadi.domain.repository.PhraseRepository
import app.redlight.hanmadi.domain.repository.ProgressRepository
import app.redlight.hanmadi.domain.repository.SoundRepository
import app.redlight.hanmadi.domain.repository.SpeechRepository
import app.redlight.hanmadi.domain.repository.VehicleRepository
import android.app.Application
import android.content.Context

class HanmadiApp : Application() {
    val container by lazy { AppContainer(this) }
}

/** 수동 DI. Pleos 차량 서비스가 없는 기기에서는 가짜 구현을 쓴다 */
class AppContainer(context: Context) {

    val isPleosDevice: Boolean = runCatching {
        context.packageManager.getPackageInfo(PLEOS_CAR_SERVICE, 0)
    }.isSuccess

    /** 가짜일 때만 값이 있다. 화면의 디버그 패널이 이걸로 차량 상태를 바꾼다 */
    val fakeVehicle: FakeVehicleRepository? = if (isPleosDevice) null else FakeVehicleRepository()

    val vehicleRepository: VehicleRepository = fakeVehicle ?: PleosVehicleRepository(context)
    val speechRepository: SpeechRepository =
        if (!isPleosDevice) {
            FakeSpeechRepository()
        } else if (BuildConfig.PLEOS_USE_SERVER) {
            PleosSpeechRepository(context, BuildConfig.PLEOS_CLIENT_ID, BuildConfig.PLEOS_CLIENT_SECRET)
        } else {
            PleosSpeechRepository(context) // 차량 내부 엔진(ON_DEVICE)만
        }
    val audioFocusRepository: AudioFocusRepository = AndroidAudioFocusRepository(context)
    val soundRepository: SoundRepository = ToneSoundRepository()
    val phraseRepository: PhraseRepository = AssetPhraseRepository(context)
    val progressRepository: ProgressRepository = PrefsProgressRepository(context)
    val stopPractice = StopPracticeUseCase(speechRepository)

    private companion object {
        const val PLEOS_CAR_SERVICE = "ai.pleos.service.car"
    }
}
