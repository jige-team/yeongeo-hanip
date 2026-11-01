package app.redlight.hanmadi.domain

import app.redlight.hanmadi.domain.model.PhraseSet
import org.junit.Assert.assertEquals
import org.junit.Test

class PhraseSetTest {

    @Test
    fun `오후 2시 전은 출근길 업무 영어, 그 뒤는 퇴근길 일상 영어`() {
        assertEquals(PhraseSet.WORK, PhraseSet.forHour(7))
        assertEquals(PhraseSet.WORK, PhraseSet.forHour(13))
        assertEquals(PhraseSet.DAILY, PhraseSet.forHour(14))
        assertEquals(PhraseSet.DAILY, PhraseSet.forHour(19))
    }
}
