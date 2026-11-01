package app.redlight.hanmadi.data

import app.redlight.hanmadi.domain.AnswerJudge
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.util.Log
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInitListener

/**
 * 디버그 전용 진단: 마이크 대신 녹음 파일(PCM)을 인식 엔진에 바로 넣는다.
 *
 * 에뮬레이터 마이크 경로(Mac 마이크 → 에뮬레이터, 1~3초 지연)를 건너뛰고 엔진 자체의 인식률을 잰다.
 * 같은 파일로 원어민 합성음과 사용자 녹음을 비교하면, 문제가 발음인지 마이크 경로인지 가를 수 있다.
 *
 * - 파일: 앱 내부 files/probe/ 의 16kHz·16bit·mono PCM, 목록은 targets.txt ("파일명|목표 표현")
 * - sendAudio()는 ON_DEVICE 모드에서만 된다 [공식: SDK 문서 "오디오 데이터 전송"]
 * - 결과는 "파일 인식" 로그로 남고 tools/probe_stt.sh가 표로 만든다
 */
class FileSttProbe(private val context: Context) {

    suspend fun run() {
        val dir = File(context.filesDir, "probe")
        val entries = File(dir, "targets.txt").takeIf { it.exists() }?.readLines()
            ?.mapNotNull { line -> line.split('|').takeIf { it.size == 2 }?.let { it[0] to it[1] } }
            .orEmpty()
        if (entries.isEmpty()) {
            Log.w(TAG, "파일 인식: files/probe/targets.txt가 없습니다")
            return
        }

        val stt = SpeechToText(context, SttMode.ON_DEVICE)
        suspendCancellableCoroutine { cont ->
            stt.initialize(object : SttInitListener {
                override fun onSuccess() = cont.resume(Unit)
                override fun onFailure(errorType: SttError) = cont.resume(Unit)
            })
        }
        val finals = Channel<String>(Channel.UNLIMITED)
        var lastText = ""
        stt.addListener(object : ResultListener {
            override fun onReady() = Unit
            override fun onStartedRecognition() = Unit
            override fun onUpdated(stt: String, completed: Boolean) {
                lastText = stt
                if (completed) finals.trySend(stt)
            }
            override fun onUpdatedEpdData(on: Long, off: Long) = Unit
            override fun onUpdatedRms(rms: Double) = Unit
            override fun onEndedRecognition() = Unit
            override fun onError(errorType: SttError) { finals.trySend("") }
        })
        stt.setLocale(Locale.US)

        Log.d(TAG, "파일 인식 시작 ${entries.size}개")
        for ((name, target) in entries) {
            val pcm = File(dir, name).takeIf { it.exists() }?.readBytes() ?: continue
            lastText = ""
            while (finals.tryReceive().isSuccess) Unit // 앞 파일의 늦은 결과를 비운다
            // 실제 마이크처럼 100ms(3200바이트)씩 흘려보낸다 [공식: SDK 예제]
            pcm.asList().chunked(BYTES_PER_100_MS).forEach { chunk ->
                stt.sendAudio(chunk.toByteArray())
                delay(100)
            }
            stt.completeAudioSend()
            val heard = withTimeoutOrNull(RESULT_WAIT_MILLIS) { finals.receive() } ?: lastText
            val judgement = AnswerJudge.judge(target, heard)
            Log.d(TAG, "파일 인식 file=\"$name\" target=\"$target\" heard=\"$heard\" success=${judgement.success}")
            delay(500)
        }
        Log.d(TAG, "파일 인식 끝")
        stt.stop()
    }

    private companion object {
        const val TAG = "Probe"
        const val BYTES_PER_100_MS = 3_200
        const val RESULT_WAIT_MILLIS = 8_000L
    }
}
