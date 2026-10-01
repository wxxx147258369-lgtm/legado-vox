package io.legado.app.domain.model.readaloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelVoiceCastingRulesTest {

    @Test
    fun `smooth mode narrator uses one stable male pool`() {
        assertEquals(
            "zh-CN-YunyangNeural",
            NovelVoiceCastingRules.preferredNames(cue(SpeechRoleType.Narrator), "").first(),
        )
    }

    @Test
    fun `all male roles collapse to the same stable male voice`() {
        assertEquals(
            "zh-CN-YunxiNeural",
            NovelVoiceCastingRules.preferredNames(cue(), "male").first(),
        )
    }

    @Test
    fun `all female roles collapse to the same stable female voice`() {
        assertEquals(
            "zh-CN-XiaoxiaoNeural",
            NovelVoiceCastingRules.preferredNames(cue(), "female").first(),
        )
    }

    @Test
    fun `automatic voices are mainland Mandarin only`() {
        val names = NovelVoiceCastingRules.NARRATOR +
            NovelVoiceCastingRules.MALE +
            NovelVoiceCastingRules.FEMALE
        assertTrue(names.all { it.startsWith("zh-CN-") })
    }

    private fun cue(
        roleType: SpeechRoleType = SpeechRoleType.Character,
    ) = ReadAloudPlaybackCue(
        text = "测试",
        chapterStart = 0,
        chapterEnd = 2,
        paragraphIndex = 0,
        voice = null,
        fallbackVoices = emptyList(),
        roleType = roleType,
        characterId = null,
    )
}
