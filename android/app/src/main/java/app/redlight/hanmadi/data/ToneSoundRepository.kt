package app.redlight.hanmadi.data

import app.redlight.hanmadi.domain.repository.SoundEffect
import app.redlight.hanmadi.domain.repository.SoundRepository
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 효과음을 코드로 만든다. 음원 파일을 쓰지 않으니 저작권·라이선스 문제가 없다(핵심 규칙 9).
 *
 * 음 하나 = 사인파 + 한 옥타브 위 배음을 조금 섞고, 빨리 올라갔다 천천히 사라지게 만든 "띵" 소리.
 * 음을 이어 붙여 짧은 멜로디를 만든다.
 */
class ToneSoundRepository : SoundRepository {

    /** 음 하나: 주파수(Hz)와 길이(ms) */
    private data class Note(val hz: Double, val millis: Int)

    override suspend fun play(effect: SoundEffect) = withContext(Dispatchers.Default) {
        val notes = melodyFor(effect)
        val pcm = synthesize(notes)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            delay(notes.sumOf { it.millis }.toLong() + TAIL_MILLIS)
        } finally {
            track.release()
        }
    }

    private fun melodyFor(effect: SoundEffect): List<Note> = when (effect) {
        // 높은 음 하나: "이제 말하세요"
        SoundEffect.YOUR_TURN -> listOf(Note(C6, 140))
        // 맞혔을 때는 매번 같은 소리면 질리므로 세 가지 중 하나
        SoundEffect.SUCCESS -> SUCCESS_MELODIES.random()
        SoundEffect.COMBO -> listOf(C5, E5, G5, C6, E6).map { Note(it, 75) }
        // 내려가는 두 음: "아쉬워요, 한 번 더"
        SoundEffect.RETRY -> listOf(Note(E5, 130), Note(C5, 180))
        SoundEffect.MOVE_ON -> listOf(Note(A4, 260))
    }

    private fun synthesize(notes: List<Note>): ShortArray {
        val samples = ArrayList<Short>()
        for (note in notes) {
            val count = SAMPLE_RATE * note.millis / 1000
            for (i in 0 until count) {
                val t = i.toDouble() / SAMPLE_RATE
                val attack = (i / (SAMPLE_RATE * ATTACK_SECONDS)).coerceAtMost(1.0)
                val envelope = attack * exp(-t * DECAY)
                val wave = sin(2 * PI * note.hz * t) + 0.3 * sin(2 * PI * note.hz * 2 * t)
                samples += (wave / 1.3 * envelope * VOLUME * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return samples.toShortArray()
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val ATTACK_SECONDS = 0.005
        const val DECAY = 6.0
        /** 음성 안내보다 작게 둔다 */
        const val VOLUME = 0.35
        const val TAIL_MILLIS = 80L

        // 음 높이(Hz)
        const val A4 = 440.0
        const val C5 = 523.25
        const val E5 = 659.25
        const val G5 = 783.99
        const val C6 = 1046.5
        const val E6 = 1318.5

        val SUCCESS_MELODIES = listOf(
            listOf(Note(E5, 90), Note(G5, 90), Note(C6, 160)),
            listOf(Note(C5, 110), Note(E5, 170)),
            listOf(Note(G5, 90), Note(C6, 90), Note(E6, 160)),
        )
    }
}
