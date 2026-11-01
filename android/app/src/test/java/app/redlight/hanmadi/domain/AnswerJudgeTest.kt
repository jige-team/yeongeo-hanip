package app.redlight.hanmadi.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerJudgeTest {

    @Test
    fun `대소문자와 문장부호는 무시한다`() {
        assertTrue(AnswerJudge.judge("Could you share your screen?", "could you share your screen").success)
    }

    @Test
    fun `아포스트로피 유무는 같게 본다`() {
        assertTrue(AnswerJudge.judge("Let's circle back to this later.", "lets circle back to this later").success)
    }

    @Test
    fun `긴 문장은 20퍼센트까지 놓쳐도 성공`() {
        // 6단어 중 1단어(20% 이하) 누락
        val result = AnswerJudge.judge("Let's circle back to this later.", "let's back to this later")
        assertTrue(result.success)
        assertEquals(listOf("circle"), result.missingWords)
    }

    @Test
    fun `많이 놓치면 실패하고 놓친 단어를 원래 순서대로 알려준다`() {
        val result = AnswerJudge.judge("Let's circle back to this later.", "to circle")
        assertFalse(result.success)
        assertEquals(listOf("lets", "back", "this", "later"), result.missingWords)
    }

    @Test
    fun `아무것도 못 들으면 실패`() {
        assertFalse(AnswerJudge.judge("circle back", "").success)
    }

    @Test
    fun `같은 단어가 두 번 나오면 두 번 들려야 한다`() {
        val result = AnswerJudge.judge("one by one", "one by")
        assertEquals(listOf("one"), result.missingWords)
    }

    // 아래는 2026-11-01 에뮬레이터에서 실제로 나온 인식 결과다

    @Test
    fun `어미만 다르면 같은 단어로 본다`() {
        assertTrue(AnswerJudge.judge("share your screen", "sharing your screen").success)
    }

    @Test
    fun `짧은 표현에서 기능어 하나는 틀려도 된다`() {
        assertTrue(AnswerJudge.judge("on mute", "of mute").success)
    }

    @Test
    fun `4글자 이상 핵심 단어는 한 글자 차이까지 같다`() {
        assertTrue(AnswerJudge.judge("on mute", "or mut").success)
    }

    @Test
    fun `핵심 단어가 빠지면 실패`() {
        val result = AnswerJudge.judge("push the meeting", "crystal meeting")
        assertFalse(result.success)
        assertEquals(listOf("push", "the"), result.missingWords)
        assertFalse(AnswerJudge.judge("by tomorrow", "by").success)
    }

    @Test
    fun `기능어는 한 글자만 달라도 다른 단어다`() {
        assertFalse(AnswerJudge.sameWord("on", "of"))
    }

    @Test
    fun `짧은 표현에서 기능어 두 개를 놓치면 실패`() {
        assertFalse(AnswerJudge.judge("on the same page", "same page").success)
    }

    @Test
    fun `짧은 핵심 단어는 한 글자 차이를 봐주지 않는다`() {
        // cut과 cat처럼 3글자 이하는 한 글자로 뜻이 바뀐다
        assertFalse(AnswerJudge.judge("cut the budget", "cat the budget").success)
    }

    // 아래는 2026-11-01 사용자 녹음을 엔진에 넣었을 때 실제로 나온 결과다 (소리 기준 비교)

    @Test
    fun `소리가 비슷하면 통과 - 한국어 화자에게 흔한 차이`() {
        assertTrue(AnswerJudge.judge("push the meeting", "push the meaning").success) // t가 n처럼
        assertTrue(AnswerJudge.judge("more slowly", "more solely").success) // s와 l 사이 모음
        assertTrue(AnswerJudge.judge("walk you through", "walk your thr").success)
        assertTrue(AnswerJudge.judge("by tomorrow", "by tomorr").success)
        assertTrue(AnswerJudge.judge("on mute", "on mood").success)
        assertTrue(AnswerJudge.judge("wrap up", "rabop up").success)
    }

    @Test
    fun `소리가 다르면 여전히 실패`() {
        assertFalse(AnswerJudge.judge("circle back", "soccer back").success)
        assertFalse(AnswerJudge.judge("share your screen", "share your sc").success)
        assertFalse(AnswerJudge.judge("seat taken", "seek taken").success)
        assertFalse(AnswerJudge.judge("push the meeting", "crystal meeting").success)
    }

    @Test
    fun `소리 열쇠`() {
        assertEquals("mtnk", AnswerJudge.soundKey("meeting"))
        assertEquals("mnk", AnswerJudge.soundKey("meaning"))
        assertEquals("sl", AnswerJudge.soundKey("slowly"))
        assertEquals(AnswerJudge.soundKey("mute"), AnswerJudge.soundKey("mood"))
    }

    @Test
    fun `틀리면 놓친 단어가 무엇으로 들렸는지 짝지어 준다`() {
        val result = AnswerJudge.judge("circle back", "soccer back")
        assertEquals(listOf("circle" to "soccer"), result.misheard)
    }

    @Test
    fun `비슷한 단어가 없으면 짝짓지 않는다`() {
        assertTrue(AnswerJudge.judge("circle back", "back").misheard.isEmpty())
    }
}
