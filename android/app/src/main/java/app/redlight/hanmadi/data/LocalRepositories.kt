package app.redlight.hanmadi.data

import app.redlight.hanmadi.domain.model.Phrase
import app.redlight.hanmadi.domain.model.PhraseSet
import app.redlight.hanmadi.domain.model.PracticeResult
import app.redlight.hanmadi.domain.model.Streak
import app.redlight.hanmadi.domain.repository.PhraseRepository
import app.redlight.hanmadi.domain.repository.ProgressRepository
import android.content.Context
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** assets/phrases.json에서 세트별로 오늘의 문장을 고른다. 날짜마다 10개씩 돌아가며 나온다 */
class AssetPhraseRepository(private val context: Context) : PhraseRepository {

    @Serializable
    private data class PhraseDto(val id: String, val set: String, val en: String, val ko: String, val chunk: String, val chunkKo: String)

    private val all: List<Phrase> by lazy {
        val text = context.assets.open("phrases.json").bufferedReader().use { it.readText() }
        Json.decodeFromString<List<PhraseDto>>(text).map {
            Phrase(it.id, it.en, it.ko, it.chunk, it.chunkKo, PhraseSet.valueOf(it.set.uppercase()))
        }
    }

    override suspend fun todaysPhrases(set: PhraseSet): List<Phrase> = withContext(Dispatchers.IO) {
        val pool = all.filter { it.set == set }
        val start = ((LocalDate.now().toEpochDay() * PER_DAY) % pool.size).toInt()
        List(PER_DAY) { pool[(start + it) % pool.size] }
    }

    private companion object {
        const val PER_DAY = 10
    }
}

/** 연속 일수와 누적 성공 수만 기기에 저장한다. 음성은 저장하지 않는다 */
class PrefsProgressRepository(context: Context) : ProgressRepository {

    private val prefs = context.getSharedPreferences("progress", Context.MODE_PRIVATE)

    override suspend fun record(results: List<PracticeResult>, todayEpochDay: Long): Streak = withContext(Dispatchers.IO) {
        val lastDay = prefs.getLong(KEY_LAST_DAY, Long.MIN_VALUE)
        val days = prefs.getInt(KEY_DAYS, 0)
        val newDays = when {
            results.isEmpty() -> days
            lastDay == todayEpochDay -> days
            lastDay == todayEpochDay - 1 -> days + 1
            else -> 1
        }
        val newTotal = prefs.getInt(KEY_TOTAL, 0) + results.count { it.judgement.success }
        prefs.edit()
            .putInt(KEY_DAYS, newDays)
            .putInt(KEY_TOTAL, newTotal)
            .apply { if (results.isNotEmpty()) putLong(KEY_LAST_DAY, todayEpochDay) }
            .apply()
        Streak(newDays, newTotal)
    }

    override suspend fun streak(): Streak = withContext(Dispatchers.IO) {
        Streak(prefs.getInt(KEY_DAYS, 0), prefs.getInt(KEY_TOTAL, 0))
    }

    private companion object {
        const val KEY_LAST_DAY = "last_day"
        const val KEY_DAYS = "days"
        const val KEY_TOTAL = "total"
    }
}
