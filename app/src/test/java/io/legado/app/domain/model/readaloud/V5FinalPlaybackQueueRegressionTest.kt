package io.legado.app.domain.model.readaloud

import org.junit.Assert.assertEquals
import org.junit.Test

class V5FinalPlaybackQueueRegressionTest {

    @Test
    fun `known same character contiguous cues merge`() {
        val first = item("别急，", 0, "a")
        val second = item("听我说。", first.segment.text.length, "a")
        val queue = ReadAloudPlaybackQueue.from(listOf(first, second))
        assertEquals(1, queue.cues.size)
        assertEquals("别急，听我说。", queue.cues.single().text)
    }

    @Test
    fun `different characters never merge`() {
        val first = item("你是谁？", 0, "a")
        val second = item("我是李四。", first.segment.text.length, "b")
        val queue = ReadAloudPlaybackQueue.from(listOf(first, second))
        assertEquals(2, queue.cues.size)
    }

    private fun item(
        text: String,
        start: Int,
        characterId: String,
    ): SpeechPlanItem = SpeechPlanItem(
        segment = ChapterSpeechSegment(
            id = "$start-$characterId",
            analysisId = "analysis",
            bookUrl = "book",
            chapterIndex = 0,
            paragraphIndex = 0,
            start = start,
            end = start + text.length,
            chapterPosition = start,
            text = text,
            roleType = SpeechRoleType.Character,
            characterId = characterId,
            source = SpeechResolutionSource.Rule,
        ),
        voice = null,
        fallbackVoices = emptyList(),
    )
}
