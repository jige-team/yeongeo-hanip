package app.redlight.hanmadi.presentation

import app.redlight.hanmadi.data.FakeVehicleRepository
import app.redlight.hanmadi.domain.DrivePhaseDetector
import app.redlight.hanmadi.domain.StopPracticeUseCase
import app.redlight.hanmadi.domain.TripTracker
import app.redlight.hanmadi.domain.model.DrivePhase
import app.redlight.hanmadi.domain.model.DriveSignal
import app.redlight.hanmadi.domain.model.Gear
import app.redlight.hanmadi.domain.model.Judgement
import app.redlight.hanmadi.domain.model.Language
import app.redlight.hanmadi.domain.model.PhraseSet
import app.redlight.hanmadi.domain.model.PracticeEvent
import app.redlight.hanmadi.domain.repository.AudioFocusRepository
import app.redlight.hanmadi.domain.repository.FocusMode
import app.redlight.hanmadi.domain.repository.PhraseRepository
import app.redlight.hanmadi.domain.repository.SoundEffect
import app.redlight.hanmadi.domain.repository.SoundRepository
import app.redlight.hanmadi.domain.repository.ProgressRepository
import app.redlight.hanmadi.domain.repository.SpeechRepository
import app.redlight.hanmadi.domain.repository.VehicleRepository
import android.Manifest
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 운전 상태에 따라 듣기 → 말하기 → 읽기를 바꾼다.
 *
 * - 주행 중: 다음에 말할 문장을 미리 들려준다 (previewJob)
 * - 정차 중: 한 번 말하기 연습을 돌린다 (practiceJob). 출발하면 이 Job을 취소해서 즉시 멈춘다
 * - 주차: 결과를 저장하고, 화면으로 다시 읽기를 허용한다
 */
class DriveViewModel(
    private val vehicle: VehicleRepository,
    private val speech: SpeechRepository,
    private val audioFocus: AudioFocusRepository,
    private val sound: SoundRepository,
    private val phrases: PhraseRepository,
    private val progress: ProgressRepository,
    private val stopPractice: StopPracticeUseCase,
    private val fakeVehicle: FakeVehicleRepository?,
) : ViewModel() {

    private val _state = MutableStateFlow(DriveState())
    val state: StateFlow<DriveState> = _state.asStateFlow()

    private val _sideEffect = Channel<DriveSideEffect>(Channel.BUFFERED)
    val sideEffect: Flow<DriveSideEffect> = _sideEffect.receiveAsFlow()

    private val detector = DrivePhaseDetector()
    private val trip = TripTracker()
    private var latestSignal = DriveSignal()

    private var previewJob: Job? = null
    private var practiceJob: Job? = null
    private var previewedIndex = -1
    /** 이번 정차에서 표현 하나를 끝냈는지 (맞혔거나 기회를 다 씀) */
    private var finishedThisStop = false
    /** 지금 표현을 끝까지 말한 횟수. 출발해도 유지해서 다음 정차에 이어서 한다 */
    private var attempt = 0
    /** 틀려서 다시 말할 표현의 결과가 results 맨 끝에 있다 */
    private var hasPendingResult = false
    private var savedResultCount = 0
    /** 새 운행이 막 시작됐다. 다음 미리 듣기 앞에 인사를 붙인다 */
    private var greetPending = false
    private var started = false

    fun onIntent(intent: DriveIntent) {
        when (intent) {
            DriveIntent.Start -> viewModelScope.launch {
                // 오늘의 표현은 권한과 상관없이 바로 보여 준다. 권한 대화상자 뒤에 "0개"가 보이지 않게
                if (_state.value.phrases.isEmpty()) {
                    loadPhrases(PhraseSet.forHour(LocalTime.now().hour))
                    val streak = progress.streak()
                    reduce { copy(streak = streak) }
                }
                _sideEffect.send(
                    DriveSideEffect.RequestPermissions(
                        listOf(Manifest.permission.RECORD_AUDIO, "android.car.permission.CAR_SPEED"),
                    ),
                )
            }
            DriveIntent.PermissionsHandled -> start()
            is DriveIntent.ReadAloud -> readAloud(intent.phraseId)
            is DriveIntent.Debug -> fakeVehicle?.set(intent.change)
            DriveIntent.StartMeasure -> startMeasure()
        }
    }

    private fun start() {
        if (started) return
        started = true

        viewModelScope.launch {
            vehicle.driveSignal().collect { signal ->
                Log.d(TAG, "신호 $signal")
                latestSignal = signal
                if (fakeVehicle != null) reduce { copy(debugSignal = signal) }
                evaluate()
            }
        }
        // 정차가 2초 이어졌는지는 신호가 안 바뀌어도 시간이 지나야 알 수 있어서 주기적으로 다시 판단한다
        viewModelScope.launch {
            while (true) {
                delay(TICK_MILLIS)
                evaluate()
            }
        }
    }

    private fun evaluate() {
        val previous = _state.value.phase
        val phase = detector.update(latestSignal, System.currentTimeMillis())
        reduce { copy(isJam = detector.isJam) }
        if (phase == previous) {
            if (phase == DrivePhase.MOVING) previewNextIfNeeded()
            return
        }
        Log.d(TAG, "상태 $previous → $phase (정체=${detector.isJam})")
        reduce { copy(phase = phase) }
        onPhaseChanged(phase, previous)
    }

    private fun onPhaseChanged(phase: DrivePhase, previous: DrivePhase) {
        // 새 정차가 시작될 때만 "이번 정차에서 끝냄"을 지운다.
        // 회전 대기·출발 직전은 같은 정차 안이라 지우지 않는다
        if (phase == DrivePhase.MOVING || phase == DrivePhase.PARKED || phase == DrivePhase.SESSION_END) finishedThisStop = false
        // 운행의 시작·끝은 P단만이 아니라 운전석 문으로 가른다 (TripTracker).
        // 앱을 켠 직후 기어 값이 오기 전의 MOVING은 "애매하면 주행 중" 규칙 때문이라 운행 시작으로 세지 않는다
        // (2026-11-01 실측: 이것 때문에 앱을 켜자마자 마무리 인사가 나왔다)
        if (latestSignal.gear != Gear.UNKNOWN) {
            when (trip.onPhase(phase, System.currentTimeMillis())) {
                TripTracker.Event.STARTED -> onTripStarted()
                TripTracker.Event.ENDED -> onTripEnded()
                null -> Unit
            }
        }

        when (phase) {
            DrivePhase.MOVING -> {
                // 출발: 말하기를 즉시 멈춘다
                cancelPractice()
                previewNextIfNeeded()
            }
            // 화면만 숨긴다. 진행 중인 말하기는 속도가 붙을 때(MOVING) 끊는다
            DrivePhase.ABOUT_TO_MOVE -> previewJob?.cancel()
            // 회전 대기: 말하기를 바로 멈춘다. 깜빡이를 끄고 계속 서 있으면 STOPPED로 돌아와 이어서 한다
            DrivePhase.TURN_WAIT -> {
                previewJob?.cancel()
                cancelPractice()
            }
            DrivePhase.STOPPED_SHORT, DrivePhase.STOPPED_LONG -> {
                previewJob?.cancel()
                startPracticeIfAllowed()
            }
            DrivePhase.PARKED, DrivePhase.SESSION_END -> {
                previewJob?.cancel()
                cancelPractice()
                // 주차하면 다시 말하기를 끝낸다. 틀린 결과는 틀린 대로 저장한다
                if (hasPendingResult) {
                    hasPendingResult = false
                    attempt = 0
                    reduce { copy(currentIndex = (currentIndex + 1) % phrases.size.coerceAtLeast(1)) }
                }
                saveProgress()
            }
        }
    }

    private suspend fun loadPhrases(set: PhraseSet) {
        val today = phrases.todaysPhrases(set)
        reduce { copy(phraseSet = set, phrases = today, currentIndex = 0) }
    }

    /**
     * 새 운행이 시작됐다: 인사하고, 시간대가 바뀌었으면 세트를 바꾼다(출근길 업무 영어 → 퇴근길 일상 영어).
     * 인사는 미리 듣기 앞에 붙여서 한 번 들려준다
     */
    private fun onTripStarted() {
        greetPending = true
        previewedIndex = -1
        val set = PhraseSet.forHour(LocalTime.now().hour)
        if (set != _state.value.phraseSet) viewModelScope.launch { loadPhrases(set) }
    }

    /** 운행이 끝났다(내림): 결과를 저장한 뒤 오늘 결과를 소리로 정리해 준다 */
    private fun onTripEnded() {
        val saving = saveProgress()
        viewModelScope.launch {
            saving?.join() // 연속 일수가 갱신된 뒤에 말한다
            announce(farewell())
        }
    }

    private fun farewell(): String {
        val s = _state.value
        val streak = if (s.streak.days > 0) " 연속 ${s.streak.days}일째예요." else ""
        return if (s.results.isEmpty()) {
            "오늘은 연습한 표현이 없어요. 다음 운행에서 만나요."
        } else {
            "오늘 ${s.results.size}개 중 ${s.successCount}개 성공했어요.$streak 수고했어요."
        }
    }

    /** 짧은 안내 한마디. 음악은 소리만 줄이고, 끝나면 돌려준다 */
    private suspend fun announce(text: String) {
        audioFocus.acquire(FocusMode.DUCK)
        try {
            speech.speak(text, Language.KOREAN)
        } finally {
            if (practiceJob?.isActive != true) audioFocus.release()
        }
    }

    private fun greeting(): String {
        val s = _state.value
        val streak = if (s.streak.days > 0) " 연속 ${s.streak.days}일째예요." else ""
        return "오늘은 ${s.phraseSet.label} ${s.phrases.size}개예요.$streak 빨간불에서 만나요."
    }

    private fun previewNextIfNeeded() {
        val index = _state.value.currentIndex
        val phrase = _state.value.current ?: return
        if (previewedIndex == index || previewJob?.isActive == true || practiceJob?.isActive == true) return
        previewedIndex = index
        // 다음 정차에서 말할 짧은 표현을 뜻 → 영어 순서로 미리 들려준다. 그동안 음악은 소리만 줄인다
        audioFocus.acquire(FocusMode.DUCK)
        previewJob = viewModelScope.launch {
            try {
                if (greetPending) {
                    greetPending = false
                    speech.speak(greeting(), Language.KOREAN)
                }
                speech.speak(phrase.keyChunkKorean, Language.KOREAN)
                speech.speak(phrase.keyChunk, Language.ENGLISH)
            } finally {
                // 정차해서 말하기로 넘어갔으면 그쪽이 포커스를 쥐고 있으니 돌려주지 않는다
                if (practiceJob?.isActive != true) audioFocus.release()
            }
        }
    }

    /**
     * 정차 중 말하기 한 번을 시작한다.
     * - 틀리면 놓친 단어를 들려준 뒤 같은 표현을 다시 말한다 (최대 [MAX_ATTEMPTS]번)
     * - 맞히거나 기회를 다 쓰면: 짧은 정차는 거기서 끝, 긴 정차는 다음 표현으로 이어 간다
     */
    private fun startPracticeIfAllowed() {
        val phrase = _state.value.current ?: return
        if (practiceJob?.isActive == true) return
        if (finishedThisStop && _state.value.phase == DrivePhase.STOPPED_SHORT) return

        // 정차 중에는 항상 짧은 표현만 말한다. 문장 전체는 주차 후 복습에서 읽는다.
        // 마이크에 음악이 섞이지 않도록 말하는 동안 음악을 잠깐 멈춘다
        audioFocus.acquire(FocusMode.PAUSE)
        practiceJob = viewModelScope.launch {
            var tryAgain = false
            stopPractice(phrase, chunkOnly = true).collect { event ->
                when (event) {
                    PracticeEvent.Prompting -> reduce {
                        copy(stop = StopUi(StopUi.Step.PROMPTING, phrase = phrase, attempt = attempt + 1, maxAttempts = MAX_ATTEMPTS))
                    }
                    PracticeEvent.Listening -> {
                        reduce { copy(stop = stop.copy(step = StopUi.Step.LISTENING)) }
                        playSound(SoundEffect.YOUR_TURN)
                    }
                    is PracticeEvent.Volume -> reduce { copy(stop = stop.copy(rms = event.rms)) }
                    is PracticeEvent.Heard -> Unit // 운전자가 글자를 읽지 않도록 들은 문장은 띄우지 않는다
                    is PracticeEvent.Judged -> {
                        logJudged(event)
                        onJudged(event.judgement)
                    }
                    is PracticeEvent.Finished -> {
                        // 끝까지 말한 시도만 센다. 도중에 출발해서 끊긴 시도는 기회를 쓰지 않는다
                        attempt++
                        tryAgain = !event.result.judgement.success && attempt < MAX_ATTEMPTS
                        // 같은 표현을 다시 말한 결과는 앞 결과를 바꾼다. 오늘 기록은 표현 하나에 하나다
                        val replacesLast = hasPendingResult
                        hasPendingResult = tryAgain
                        if (!tryAgain) {
                            attempt = 0
                            finishedThisStop = true
                        }
                        reduce {
                            copy(
                                results = (if (replacesLast) results.dropLast(1) else results) + event.result,
                                currentIndex = if (tryAgain) currentIndex else (currentIndex + 1) % phrases.size.coerceAtLeast(1),
                            )
                        }
                    }
                }
            }
            if (tryAgain || _state.value.phase == DrivePhase.STOPPED_LONG) {
                // 다음 시도까지 음악은 멈춘 채로 둔다. 1.5초 틈에 음악이 켜졌다 꺼지면 더 거슬린다
                delay(NEXT_PHRASE_PAUSE_MILLIS)
                // 지금 이 Job이 아직 practiceJob이라 비워 둬야 다음 시도를 시작할 수 있다
                practiceJob = null
                if (_state.value.phase.isStopped) startPracticeIfAllowed()
            } else {
                audioFocus.release()
            }
        }
    }

    /** 판정이 나오자마자 화면과 효과음을 바꾼다. 결과 기록은 피드백 음성이 끝난 뒤(Finished)에 한다 */
    private fun onJudged(judgement: Judgement) {
        val willRetry = !judgement.success && attempt + 1 < MAX_ATTEMPTS
        // 다시 말하는 중이면 맨 끝 결과는 이 표현의 앞선 실패라서 연속 기록에서 뺀다
        val previous = _state.value.results.let { if (hasPendingResult) it.dropLast(1) else it }
        val combo = if (judgement.success) previous.takeLastWhile { it.judgement.success }.size + 1 else 0
        reduce { copy(stop = stop.copy(step = StopUi.Step.DONE, lastJudgement = judgement, willRetry = willRetry)) }
        playSound(
            when {
                judgement.success && combo >= COMBO_COUNT -> SoundEffect.COMBO
                judgement.success -> SoundEffect.SUCCESS
                willRetry -> SoundEffect.RETRY
                else -> SoundEffect.MOVE_ON
            },
        )
    }

    /** 인식률 측정용 기록 (tools/measure_stt.sh가 읽는다) */
    private fun logJudged(event: PracticeEvent.Judged) {
        Log.d(TAG, "판정 target=\"${event.target}\" success=${event.judgement.success}")
    }

    private fun playSound(effect: SoundEffect) {
        viewModelScope.launch { sound.play(effect) }
    }

    private fun cancelPractice() {
        practiceJob?.cancel()
        practiceJob = null
        speech.stopAll()
        audioFocus.release()
        reduce { copy(stop = StopUi()) }
    }

    /** 아직 저장하지 않은 결과를 저장한다. 저장할 게 없으면 null */
    private fun saveProgress(): Job? {
        val results = _state.value.results
        if (results.size == savedResultCount) return null
        val newResults = results.drop(savedResultCount)
        savedResultCount = results.size
        return viewModelScope.launch {
            val streak = progress.record(newResults, LocalDate.now().toEpochDay())
            reduce { copy(streak = streak) }
        }
    }

    /**
     * 인식률 측정 (디버그 빌드 전용). 주차 상태에서 업무 10개 + 일상 10개의 짧은 표현을
     * 정차 연습과 같은 순서(뜻 → 영어 → 띵 → 듣기 → 판정)로 연달아 진행한다.
     * 결과는 "판정" 로그로 남고 tools/measure_stt.sh가 표로 만든다. 연습 기록에는 남기지 않는다
     */
    private fun startMeasure() {
        // 측정 명령이 앱을 새로 띄운 경우 차량 신호가 오기 전이다. 그때는 기어를 몰라 "주행 중"으로 판단되고
        // 측정이 바로 취소된다(2026-11-01 실측). 기어 값이 들어온 뒤에 시작한다
        viewModelScope.launch {
            while (latestSignal.gear == Gear.UNKNOWN) delay(MEASURE_WAIT_STEP_MILLIS)
            delay(MEASURE_SETTLE_MILLIS)
            runMeasure()
        }
    }

    private fun runMeasure() {
        val phase = _state.value.phase
        if (phase != DrivePhase.PARKED && phase != DrivePhase.SESSION_END) {
            Log.d(TAG, "측정은 P단에서만 할 수 있어요 (지금 $phase)")
            return
        }
        if (practiceJob?.isActive == true) return
        audioFocus.acquire(FocusMode.PAUSE)
        practiceJob = viewModelScope.launch {
            try {
                val targets = phrases.todaysPhrases(PhraseSet.WORK) + phrases.todaysPhrases(PhraseSet.DAILY)
                Log.d(TAG, "측정 시작 ${targets.size}개")
                targets.forEachIndexed { index, phrase ->
                    reduce { copy(measureLabel = "측정 ${index + 1}/${targets.size}") }
                    stopPractice(phrase, chunkOnly = true).collect { event ->
                        when (event) {
                            PracticeEvent.Prompting -> reduce { copy(stop = StopUi(StopUi.Step.PROMPTING, phrase = phrase)) }
                            PracticeEvent.Listening -> {
                                reduce { copy(stop = stop.copy(step = StopUi.Step.LISTENING)) }
                                playSound(SoundEffect.YOUR_TURN)
                            }
                            is PracticeEvent.Volume -> reduce { copy(stop = stop.copy(rms = event.rms)) }
                            is PracticeEvent.Judged -> {
                                logJudged(event)
                                reduce { copy(stop = stop.copy(step = StopUi.Step.DONE, lastJudgement = event.judgement)) }
                                playSound(if (event.judgement.success) SoundEffect.SUCCESS else SoundEffect.RETRY)
                            }
                            else -> Unit
                        }
                    }
                    delay(NEXT_PHRASE_PAUSE_MILLIS)
                }
                Log.d(TAG, "측정 끝")
            } finally {
                audioFocus.release()
                reduce { copy(measureLabel = null, stop = StopUi()) }
            }
        }
    }

    /** 주차 중에만 화면으로 문장 전체를 보며 다시 읽는다 */
    private fun readAloud(phraseId: String) {
        val phase = _state.value.phase
        if (phase != DrivePhase.PARKED && phase != DrivePhase.SESSION_END) return
        val phrase = _state.value.phrases.firstOrNull { it.id == phraseId } ?: return
        if (practiceJob?.isActive == true) return
        reduce { copy(reviewingId = phraseId) }
        audioFocus.acquire(FocusMode.PAUSE)
        practiceJob = viewModelScope.launch {
            try {
                stopPractice(phrase, chunkOnly = false).collect { event ->
                    when (event) {
                        PracticeEvent.Listening -> playSound(SoundEffect.YOUR_TURN)
                        is PracticeEvent.Judged -> {
                            logJudged(event)
                            playSound(if (event.judgement.success) SoundEffect.SUCCESS else SoundEffect.RETRY)
                        }
                        is PracticeEvent.Finished -> reduce {
                            copy(reviewJudgements = reviewJudgements + (phraseId to event.result.judgement), reviewingId = null)
                        }
                        else -> Unit
                    }
                }
            } finally {
                audioFocus.release()
            }
        }
    }

    private fun reduce(transform: DriveState.() -> DriveState) = _state.update(transform)

    override fun onCleared() {
        speech.stopAll()
        audioFocus.release()
    }

    private companion object {
        const val TAG = "Drive"
        const val TICK_MILLIS = 250L
        const val NEXT_PHRASE_PAUSE_MILLIS = 1_500L
        /** 한 표현을 말할 기회. 빨간불에서 한 표현에 막혀 지치지 않도록 3번으로 둔다 [판단] */
        const val MAX_ATTEMPTS = 3
        /** 이만큼 연속으로 맞히면 따로 칭찬한다 */
        const val COMBO_COUNT = 3
        const val MEASURE_WAIT_STEP_MILLIS = 200L
        /** 기어 값이 처음 들어온 뒤 다른 신호(속도 등)가 따라 들어올 시간 */
        const val MEASURE_SETTLE_MILLIS = 1_500L
    }
}
