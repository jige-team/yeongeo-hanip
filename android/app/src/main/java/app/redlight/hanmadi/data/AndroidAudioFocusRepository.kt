package app.redlight.hanmadi.data

import app.redlight.hanmadi.domain.repository.AudioFocusRepository
import app.redlight.hanmadi.domain.repository.FocusMode
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log

/**
 * Android 오디오 포커스로 음악을 줄이거나 멈춘다. 퍼미션이 필요 없다.
 *
 * Pleos TTS·STT는 스스로 포커스를 잡지 않는다(2026-11-01 에뮬레이터 확인).
 * 그래서 음악이 나오는 중에 말하면 음악 소리가 인식 결과에 섞인다.
 */
class AndroidAudioFocusRepository(context: Context) : AudioFocusRepository {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var current: AudioFocusRequest? = null
    private var currentMode: FocusMode? = null

    override fun acquire(mode: FocusMode) {
        if (mode == currentMode) return
        release()
        val gain = when (mode) {
            FocusMode.DUCK -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            FocusMode.PAUSE -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        }
        val request = AudioFocusRequest.Builder(gain).setAudioAttributes(attributes).build()
        val result = audioManager.requestAudioFocus(request)
        Log.d(TAG, "포커스 요청 $mode → ${if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) "허용" else "거절($result)"}")
        current = request
        currentMode = mode
    }

    override fun release() {
        current?.let {
            audioManager.abandonAudioFocusRequest(it)
            Log.d(TAG, "포커스 반납 $currentMode")
        }
        current = null
        currentMode = null
    }

    private companion object {
        const val TAG = "Focus"
    }
}
