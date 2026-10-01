package io.legado.app.domain.model.readaloud

/**
 * V5 Smooth Mode automatic casting.
 *
 * AI/rules may identify character identity and gender before playback, but playback itself
 * intentionally collapses automatic voices into only three stable classes:
 * narrator / male / female.
 */
object NovelVoiceCastingRules {

    val NARRATOR = listOf(
        "zh-CN-YunyangNeural",
        "zh-CN-YunjianNeural",
    )

    val MALE = listOf(
        "zh-CN-YunxiNeural",
        "zh-CN-YunhaoNeural",
    )

    val FEMALE = listOf(
        "zh-CN-XiaoxiaoNeural",
        "zh-CN-XiaoyiNeural",
    )

    fun preferredNames(
        cue: ReadAloudPlaybackCue,
        inferredGender: String,
    ): List<String> = when {
        cue.roleType == SpeechRoleType.Narrator -> NARRATOR
        inferredGender.equals("female", ignoreCase = true) -> FEMALE
        else -> MALE
    }
}
