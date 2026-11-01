package app.redlight.hanmadi.domain

import app.redlight.hanmadi.domain.model.Judgement

/**
 * 인식된 말과 목표를 단어 단위로 비교한다.
 * 발음 점수 같은 블랙박스 대신, "목표 단어가 들렸나"만 본다.
 *
 * 규칙 (2026-11-01, 에뮬레이터 실측으로 조정):
 * - 핵심 단어(기능어가 아닌 단어)는 반드시 들려야 한다. "push the meeting"에서 push가 빠지면 실패
 * - 같은 단어로 보는 경우: 어미만 다름(sharing = share), 4글자 이상 단어에서 한 글자 차이(mut = mute)
 *   근거: 인식기가 "share your screen"을 "sharing your screen"으로, "on mute"를 "or mut"로 적었다
 * - 기능어(on, of, the, your…)는 짧은 표현에서 1개까지 놓쳐도 된다. "of mute"는 on mute로 통과
 * - 문장(5단어 이상)은 지금처럼 단어의 20%까지 놓쳐도 된다
 * - 소리가 비슷하면 같은 단어로 본다(4글자 이상 핵심 단어만). 듀오링고가 글자 대신 발음으로 채점하도록 바꾼 것과 같은 방향
 *   근거: 사용자 녹음에서 meeting → "meaning", slowly → "solely"처럼 한국어 화자에게 흔한 소리 차이로 실패가 쌓였다
 *
 * - 숫자는 영어 단어로 바꿔 비교한다. 인식기가 "ten minutes"를 "10 minutes"로 적었다(2026-11-01 사용자 지적)
 * - 들린 단어가 3글자 이상이고 목표 단어의 앞부분이면 같은 단어로 본다(min = minutes).
 *   근거: 에뮬레이터는 소리가 1~3초 늦게 들어가서 인식기가 약 4초에 귀를 닫을 때 단어 끝이 잘렸다("10 min")
 *
 * 틀렸을 때는 놓친 핵심 단어와 가장 비슷하게 들린 단어를 짝지어 알려 준다(misheard). "meeting이 meaning으로 들렸어요"
 */
object AnswerJudge {

    private const val ALLOWED_MISS_RATIO = 0.2
    private const val SHORT_CHUNK_WORDS = 4
    private const val SHORT_CHUNK_FUNCTION_MISS = 1

    /** 짧고 자주 잘못 들리는 단어. 이 단어들은 놓쳐도 뜻이 거의 바뀌지 않는다 */
    private val FUNCTION_WORDS = setOf(
        "a", "an", "the", "on", "of", "in", "to", "at", "for", "by", "it", "is",
        "i", "me", "my", "you", "your", "we", "us", "our", "this", "that", "and", "or",
    )

    fun judge(target: String, heard: String): Judgement {
        val targetWords = words(target)
        val heardWords = words(heard).toMutableList()

        val missing = targetWords.filter { word ->
            val index = heardWords.indexOfFirst { sameWord(word, it) }
            if (index >= 0) heardWords.removeAt(index) // 같은 단어가 두 번 나오면 두 번 들려야 한다
            index < 0
        }
        // 놓친 핵심 단어와 짝이 남은 들린 단어를 순서대로 짝짓는다. 잘못 들린 말은 대개 같은 자리에 온다
        // ("circle back" → "soccer back": circle 자리에 soccer). 철자가 달라도 순서로 짝이 맞는다
        val misheard = missing.filter { it !in FUNCTION_WORDS }.zip(heardWords.filter { it !in FUNCTION_WORDS })

        val success = targetWords.isNotEmpty() && if (targetWords.size <= SHORT_CHUNK_WORDS) {
            missing.none { it !in FUNCTION_WORDS } && missing.size <= SHORT_CHUNK_FUNCTION_MISS
        } else {
            missing.size <= (targetWords.size * ALLOWED_MISS_RATIO).toInt()
        }
        // 놓친 단어는 원래 순서대로 둔다. 순서를 바꾸면 "deadline the"처럼 엉뚱한 말이 된다(2026-11-01)
        return Judgement(success, missingWords = missing, misheard = misheard)
    }

    /** 소문자로 바꾸고 문장부호와 아포스트로피를 없앤다. let's와 lets를 같게 본다. 두 자리까지의 숫자는 영어 단어로 바꾼다 */
    internal fun words(text: String): List<String> =
        text.lowercase()
            .replace(Regex("['’]"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .split(' ')
            .filter { it.isNotBlank() }
            .flatMap { if (it.length <= 2 && it.all(Char::isDigit)) numberWords(it.toInt()) else listOf(it) }

    private val ONES = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    )
    private val TENS = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

    /** 0~99를 영어 단어로. 25 → twenty five */
    private fun numberWords(n: Int): List<String> = when {
        n < 20 -> listOf(ONES[n])
        n % 10 == 0 -> listOf(TENS[n / 10])
        else -> listOf(TENS[n / 10], ONES[n % 10])
    }

    internal fun sameWord(target: String, heard: String): Boolean {
        if (target == heard) return true
        // 기능어는 짧아서 한 글자만 달라도 다른 단어다(on과 of). 정확히 같아야 한다
        if (target in FUNCTION_WORDS) return false
        if (stem(target) == stem(heard)) return true
        if (maxOf(target.length, heard.length) >= 4 && editDistance(target, heard) <= 1) return true
        // 단어 끝이 잘려 들렸다(min = minutes). 너무 짧으면 아무 단어나 맞게 되므로 3글자 이상만
        if (heard.length >= 3 && target.length > heard.length && target.startsWith(heard)) return true
        return soundsAlike(target, heard)
    }

    /**
     * 소리가 비슷한가. 4글자 이상 단어만 본다(cut과 cat처럼 짧은 단어는 모음 하나로 뜻이 바뀐다).
     * 소리 열쇠가 같거나, 둘 다 3글자 이상이면서 한 글자만 다르면 같은 단어로 본다.
     */
    private fun soundsAlike(target: String, heard: String): Boolean {
        if (target.length < 4) return false
        val a = soundKey(target)
        val b = soundKey(heard)
        if (a.length < 2 || b.isEmpty()) return false
        return a == b || (a.length >= 3 && b.length >= 3 && editDistance(a, b) <= 1)
    }

    /**
     * 소리 열쇠: 비슷하게 들리는 글자를 하나로 묶고 모음은 뺀다(첫 모음만 * 로 자리를 남긴다).
     * meeting → mtnk, meaning → mnk, slowly → sl, solely → sl, mute → mt, mood → mt
     * Metaphone 같은 발음 부호화를 단순하게 줄인 것이다. 사전 발음이 아니라 "인식기가 헷갈릴 만한가"만 본다
     */
    internal fun soundKey(word: String): String {
        val w = word.lowercase().filter { it in 'a'..'z' }
            .replace("tch", "x").replace("ch", "x").replace("sh", "x").replace("ph", "f").replace("gh", "")
            .replace("th", "t").replace("ck", "k").replace("qu", "kw").replace("wr", "r").replace("wh", "w").replace("kn", "n")
        val key = StringBuilder()
        w.forEachIndexed { i, c ->
            val code = when (c) {
                'a', 'e', 'i', 'o', 'u' -> if (i == 0) '*' else null
                'h', 'w', 'y' -> null
                'c' -> if (w.getOrNull(i + 1) in SOFT_C) 's' else 'k'
                'g', 'q', 'k' -> 'k'
                'b', 'p' -> 'p'
                'd', 't' -> 't'
                'f', 'v' -> 'f'
                's', 'z' -> 's'
                'j' -> 'x'
                else -> c
            }
            if (code != null && key.lastOrNull() != code) key.append(code)
        }
        return key.toString()
    }

    private val SOFT_C = setOf('e', 'i', 'y')

    /** 어미만 떼어 낸다. 사전 수준의 어간 분석이 아니라, 인식기가 자주 붙이는 -ing/-ed/-s만 본다 */
    private fun stem(word: String): String = when {
        word.length > 5 && word.endsWith("ing") -> word.dropLast(3)
        word.length > 4 && word.endsWith("ed") -> word.dropLast(2)
        word.length > 3 && word.endsWith("s") && !word.endsWith("ss") -> word.dropLast(1)
        else -> word
    }.trimEnd('e') // share/shar(ing), close/clos(ed)를 같게 본다

    /** 한 글자 넣기·빼기·바꾸기 횟수 */
    internal fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }
}
