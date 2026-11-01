package app.redlight.hanmadi.data

import app.redlight.hanmadi.domain.model.Language
import app.redlight.hanmadi.domain.model.ListenEvent
import app.redlight.hanmadi.domain.repository.SpeechRepository
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.OnServerConnectionListener
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInitListener
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.OnServerConnectionListener as TtsServerConnectionListener
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInitListener

/**
 * Pleos TTS·STT를 감싼다.
 *
 * - SDK 리스너는 처음 초기화할 때 **한 번만** 등록하고, 이벤트는 SharedFlow로 나눠 준다.
 *   (스파이크에서 화면이 다시 만들어질 때 리스너가 두 번 등록되던 문제를 막는다)
 * - 앱 등록이 필요 없는 차량 내부 엔진(ON_DEVICE)만 쓴다.
 */
class PleosSpeechRepository(
    context: Context,
    /** Pleos Playground My Project 자격 증명. 둘 다 있으면 인식은 서버 우선(HYBRID), 없으면 차량 내부 엔진만 쓴다 */
    private val clientId: String = "",
    private val clientSecret: String = "",
) : SpeechRepository {

    private val useServer = clientId.isNotBlank() && clientSecret.isNotBlank()
    // TTS도 서버 우선(HYBRID). 차량 내부 엔진은 영어도 한국어 화자(seoyoon)로 읽어서(2026-11-01 로그) 서버 목소리를 먼저 쓴다
    private val tts = TextToSpeech(context, if (useServer) TtsMode.HYBRID else TtsMode.ON_DEVICE)
    // HYBRID: 서버로 먼저 인식하고, 네트워크가 안 되면 차량 내부 엔진으로 자동 전환한다 [공식: SDK 문서]
    private val stt = SpeechToText(context, if (useServer) SttMode.HYBRID else SttMode.ON_DEVICE)

    private val initMutex = Mutex()
    private var initialized = false

    private sealed interface TtsSignal {
        data object Started : TtsSignal
        data object Finished : TtsSignal
        data object Failed : TtsSignal
    }

    private val ttsSignals = MutableSharedFlow<TtsSignal>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val sttEvents = MutableSharedFlow<SttRaw>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private sealed interface SttRaw {
        data object Ready : SttRaw
        data class Text(val text: String, val completed: Boolean) : SttRaw
        data class Volume(val rms: Double) : SttRaw
        data object Ended : SttRaw
        data class Failed(val reason: String) : SttRaw
    }

    override suspend fun speak(text: String, language: Language) {
        ensureInitialized()
        var started = false
        try {
            withTimeoutOrNull(SPEAK_LIMIT_MILLIS) {
                ttsSignals
                    .onSubscription {
                        tts.setLocale(language.toLocale())
                        tts.speak(text, true)
                    }
                    .first { signal ->
                        when (signal) {
                            TtsSignal.Started -> { started = true; false }
                            // 시작 전에 온 끝 신호는 바로 앞에서 멈춘 문장의 것이다.
                            // (에뮬레이터 실측: 미리 듣기를 멈춘 onStop이 다음 문장의 speak 뒤에 도착했다)
                            TtsSignal.Finished -> started
                            TtsSignal.Failed -> true
                        }
                    }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            tts.stop()
            throw e
        }
    }

    override fun listen(language: Language): Flow<ListenEvent> = flow {
        ensureInitialized()
        var lastText = ""
        try {
            sttEvents
                .onSubscription {
                    stt.setLocale(language.toLocale())
                    maxRms = 0.0
                    rmsCount = 0
                    listenSession++
                    stt.request()
                }
                .transformWhile { raw ->
                    when (raw) {
                        SttRaw.Ready -> { emit(ListenEvent.Ready); true }
                        is SttRaw.Volume -> { emit(ListenEvent.Volume(raw.rms)); true }
                        is SttRaw.Text -> {
                            lastText = raw.text
                            if (raw.completed) { emit(ListenEvent.Final(raw.text)); false } else { emit(ListenEvent.Partial(raw.text)); true }
                        }
                        // 최종 결과가 1초 안에 안 오면 마지막으로 들은 말로 판정한다
                        SttRaw.Ended -> { emit(ListenEvent.Final(lastText)); false }
                        is SttRaw.Failed -> { emit(ListenEvent.Error(raw.reason)); false }
                    }
                }
                .collect { emit(it) }
        } finally {
            // 인식이 안 될 때 원인을 가르기 위한 기록: 소리가 아예 안 들어왔는지(입력 크기), 들어왔는데 못 알아들었는지
            Log.d(TAG, "듣기 끝: 입력 크기 최대=$maxRms (${rmsCount}회), 마지막 인식=\"$lastText\"")
            stt.stop()
        }
    }

    override fun stopAll() {
        if (!initialized) return
        tts.stop()
        stt.stop()
    }

    private suspend fun ensureInitialized() = initMutex.withLock {
        if (initialized) return@withLock
        suspendCancellableCoroutine { cont ->
            tts.initialize(object : TtsInitListener {
                override fun onSuccess() = cont.resume(Unit)
                override fun onFailure(errorType: TtsError) = cont.resume(Unit)
            })
        }
        suspendCancellableCoroutine { cont ->
            stt.initialize(object : SttInitListener {
                override fun onSuccess() = cont.resume(Unit)
                override fun onFailure(errorType: SttError) = cont.resume(Unit)
            })
        }
        if (useServer) {
            registerForServer()
            registerTtsForServer()
        }
        Log.d(TAG, "인식·음성 엔진: ${if (useServer) "HYBRID(서버 우선)" else "ON_DEVICE(차량 내부)"}")
        tts.addEventListener(ttsListener)
        stt.addListener(sttListener)
        initialized = true
    }

    /** 서버 인식을 쓰려면 초기화 뒤 앱 등록이 필요하다. 실패해도 HYBRID라 차량 내부 엔진으로 동작한다 */
    private suspend fun registerForServer() = suspendCancellableCoroutine { cont ->
        stt.registerApp(clientId, clientSecret, object : OnServerConnectionListener {
            override fun onConnected() {
                Log.d(TAG, "서버 인식 등록 성공")
                cont.resume(Unit)
            }
            override fun onFailed(errorType: SttError) {
                Log.w(TAG, "서버 인식 등록 실패: $errorType (차량 내부 엔진으로 동작)")
                cont.resume(Unit)
            }
        })
    }

    /** 서버 목소리를 쓰려면 TTS도 따로 앱 등록이 필요하다. 실패하면 차량 내부 목소리로 읽는다 */
    private suspend fun registerTtsForServer() = suspendCancellableCoroutine { cont ->
        tts.registerApp(clientId, clientSecret, object : TtsServerConnectionListener {
            override fun onConnected() {
                Log.d(TAG, "서버 음성 합성 등록 성공")
                cont.resume(Unit)
            }
            override fun onFailed(errorType: TtsError) {
                Log.w(TAG, "서버 음성 합성 등록 실패: $errorType (차량 내부 목소리로 동작)")
                cont.resume(Unit)
            }
        })
    }

    private val ttsListener = object : EventListener {
        override fun onReady() = Unit
        override fun onStart() { ttsSignals.tryEmit(TtsSignal.Started) }
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onDone() { ttsSignals.tryEmit(TtsSignal.Finished) }
        override fun onStop() { ttsSignals.tryEmit(TtsSignal.Finished) }
        override fun onError(errorType: TtsError) { ttsSignals.tryEmit(TtsSignal.Failed) }
    }

    private val sttListener = object : ResultListener {
        override fun onReady() { Log.d(TAG, "인식 준비"); sttEvents.tryEmit(SttRaw.Ready) }
        override fun onStartedRecognition() { Log.d(TAG, "말소리 시작 감지") }
        override fun onUpdated(stt: String, completed: Boolean) {
            Log.d(TAG, "인식 결과(완료=$completed): \"$stt\"")
            sttEvents.tryEmit(SttRaw.Text(stt, completed))
        }
        override fun onUpdatedEpdData(on: Long, off: Long) { Log.d(TAG, "발화 구간 on=$on off=$off") }
        override fun onUpdatedRms(rms: Double) {
            maxRms = maxOf(maxRms, rms)
            rmsCount++
            sttEvents.tryEmit(SttRaw.Volume(rms))
        }
        override fun onEndedRecognition() {
            Log.d(TAG, "인식 끝")
            // "인식 끝"이 최종 결과보다 먼저 올 때가 있다(에뮬레이터 실측 47ms 차이).
            // 바로 끝내면 마지막 단어("screen")가 빠진 중간 결과로 판정하므로, 최종 결과를 1초 더 기다린다
            val session = listenSession
            mainHandler.postDelayed({
                if (session == listenSession) sttEvents.tryEmit(SttRaw.Ended)
            }, FINAL_RESULT_WAIT_MILLIS)
        }
        override fun onError(errorType: SttError) { Log.d(TAG, "인식 오류: $errorType"); sttEvents.tryEmit(SttRaw.Failed(errorType.toString())) }
    }

    private fun Language.toLocale(): Locale = when (this) {
        Language.ENGLISH -> Locale.US
        Language.KOREAN -> Locale.KOREA
    }

    // 진단용: 이번 듣기에서 들어온 입력 크기
    @Volatile private var maxRms = 0.0
    @Volatile private var rmsCount = 0

    /** 듣기 요청마다 늘린다. 늦게 도착한 "인식 끝"이 다음 듣기를 끝내지 않게 한다 */
    @Volatile private var listenSession = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    private companion object {
        const val TAG = "Speech"
        const val FINAL_RESULT_WAIT_MILLIS = 1_000L
        /** 엔진이 시작·끝 신호를 주지 않아도 연습이 멈추지 않게 한다 [판단: 긴 문장도 10초 안에 읽힌다] */
        const val SPEAK_LIMIT_MILLIS = 15_000L
    }
}
