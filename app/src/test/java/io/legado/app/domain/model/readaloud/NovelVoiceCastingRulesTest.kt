package io.legado.app.domain.model.readaloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelVoiceCastingRulesTest {

    @Test
    fun `narrator prefers male Mandarin voice`() {
        val cue = cue(roleType = SpeechRoleType.Narrator)
        assertEquals(
            "zh-CN-YunyangNeural",
            NovelVoiceCastingRules.preferredNames(cue, "").first(),
        )
    }

    @Test
    fun `male lead prefers dedicated male lead pool`() {
        val cue = cue(
            profile = CharacterPerformanceProfile(
                characterId = "m1",
                role = "male_lead",
                voiceGender = "male",
            )
        )
        assertEquals(
            "zh-CN-YunxiNeural",
            NovelVoiceCastingRules.preferredNames(cue, "male").first(),
        )
    }

    @Test
    fun `female lead prefers dedicated female lead pool`() {
        val cue = cue(
            profile = CharacterPerformanceProfile(
                characterId = "f1",
                role = "female_lead",
                voiceGender = "female",
            )
        )
        assertEquals(
            "zh-CN-XiaoxiaoNeural",
            NovelVoiceCastingRules.preferredNames(cue, "female").first(),
        )
    }

    @Test
    fun `elder male is not cast as young male`() {
        val cue = cue(
            profile = CharacterPerformanceProfile(
                characterId = "old",
                voiceGender = "male",
                voiceAgeBand = "elder",
            )
        )
        assertEquals(
            "zh-CN-YunzeNeural",
            NovelVoiceCastingRules.preferredNames(cue, "male").first(),
        )
    }

    @Test
    fun `automatic rule list contains only mainland Mandarin voice ids`() {
        val samples = listOf(
            cue(roleType = SpeechRoleType.Narrator) to "",
            cue(profile = CharacterPerformanceProfile("m", voiceGender = "male")) to "male",
            cue(profile = CharacterPerformanceProfile("f", voiceGender = "female")) to "female",
        )
        samples.forEach { (cue, gender) ->
            assertTrue(
                NovelVoiceCastingRules.preferredNames(cue, gender)
                    .all { it.startsWith("zh-CN-") }
            )
        }
    }

    private fun cue(
        roleType: SpeechRoleType = SpeechRoleType.Character,
        profile: CharacterPerformanceProfile? = null,
    ) = ReadAloudPlaybackCue(
        text = "测试",
        chapterStart = 0,
        chapterEnd = 2,
        paragraphIndex = 0,
        voice = null,
        fallbackVoices = emptyList(),
        roleType = roleType,
        characterId = profile?.characterId,
        characterPerformance = profile,
    )
}
