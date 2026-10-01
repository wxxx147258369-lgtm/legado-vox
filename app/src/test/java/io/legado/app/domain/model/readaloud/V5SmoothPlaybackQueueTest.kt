package io.legado.app.domain.model.readaloud

import org.junit.Assert.assertEquals
import org.junit.Test

class V5SmoothPlaybackQueueTest {

    @Test
    fun `smooth mode merges adjacent male fragments even when character ids differ`() {
        val first = item("他说，", 0, "a", "male", "angry")
        val second = item("继续走。", first.segment.text.length, "b", "male", "calm")
        val queue = ReadAloudPlaybackQueue.from(listOf(first, second), smoothMode = true)
        assertEquals(1, queue.cues.size)
        assertEquals("他说，继续走。", queue.cues.single().text)
    }

    @Test
    fun `smooth mode keeps male and female voices separate`() {
        val first = item("走。", 0, "a", "male", "")
        val second = item("等等。", first.segment.text.length, "b", "female", "")
        val queue = ReadAloudPlaybackQueue.from(listOf(first, second), smoothMode = true)
        assertEquals(2, queue.cues.size)
    }

    @Test
    fun `smooth mode keeps narrator separate from character`() {
        val narrator = SpeechPlanItem(
            segment = ChapterSpeechSegment(
                id = "n",
                analysisId = "analysis",
                bookUrl = "book",
                chapterIndex = 0,
                paragraphIndex = 0,
                start = 0,
                end = 3,
                chapterPosition = 0,
                text = "他说：",
                roleType = SpeechRoleType.Narrator,
                source = SpeechResolutionSource.Rule,
            ),
            voice = null,
            fallbackVoices = emptyList(),
        )
        val character = item("走。", 3, "a", "male", "")
        val queue = ReadAloudPlaybackQueue.from(listOf(narrator, character), smoothMode = true)
        assertEquals(2, queue.cues.size)
    }

    private fun item(
        text: String,
        start: Int,
        characterId: String,
        gender: String,
        emotion: String,
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
            emotion = emotion,
            source = SpeechResolutionSource.Rule,
        ),
        voice = null,
        fallbackVoices = emptyList(),
        characterPerformance = CharacterPerformanceProfile(
            characterId = characterId,
            voiceGender = gender,
        ),
    )
}
