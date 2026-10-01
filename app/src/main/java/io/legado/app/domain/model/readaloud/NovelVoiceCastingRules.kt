package io.legado.app.domain.model.readaloud

/**
 * Stable automatic casting rules for Chinese fiction.
 *
 * - Narrator is male by default.
 * - Character identity decides the stable voice; emotion never changes speaker voice.
 * - Automatic casting only uses Mandarin zh-CN / zh-Hans voices.
 * - Explicit user voice bindings remain higher priority in the playback router.
 */
object NovelVoiceCastingRules {

    val NARRATOR = listOf(
        "zh-CN-YunyangNeural",
        "zh-CN-YunxiNeural",
        "zh-CN-YunjianNeural",
    )

    private val MALE_LEAD = listOf(
        "zh-CN-YunxiNeural",
        "zh-CN-YunhaoNeural",
        "zh-CN-YunjianNeural",
    )
    private val MALE_YOUNG = listOf(
        "zh-CN-YunhaoNeural",
        "zh-CN-YunxiNeural",
        "zh-CN-YunyeNeural",
    )
    private val MALE_SUPPORTING = listOf(
        "zh-CN-YunyeNeural",
        "zh-CN-YunhaoNeural",
        "zh-CN-YunzeNeural",
        "zh-CN-YunfengNeural",
    )
    private val MALE_MATURE = listOf(
        "zh-CN-YunjianNeural",
        "zh-CN-YunfengNeural",
        "zh-CN-YunzeNeural",
    )
    private val MALE_ELDER = listOf(
        "zh-CN-YunzeNeural",
        "zh-CN-YunjianNeural",
        "zh-CN-YunyangNeural",
    )
    private val MALE_COLD = listOf(
        "zh-CN-YunfengNeural",
        "zh-CN-YunyeNeural",
        "zh-CN-YunzeNeural",
    )

    private val FEMALE_LEAD = listOf(
        "zh-CN-XiaoxiaoNeural",
        "zh-CN-XiaoyiNeural",
        "zh-CN-XiaohanNeural",
    )
    private val FEMALE_YOUNG = listOf(
        "zh-CN-XiaomengNeural",
        "zh-CN-XiaoxuanNeural",
        "zh-CN-XiaozhenNeural",
        "zh-CN-XiaoyiNeural",
    )
    private val FEMALE_SUPPORTING = listOf(
        "zh-CN-XiaoyiNeural",
        "zh-CN-XiaoyanNeural",
        "zh-CN-XiaozhenNeural",
        "zh-CN-XiaohanNeural",
    )
    private val FEMALE_MATURE = listOf(
        "zh-CN-XiaomoNeural",
        "zh-CN-XiaohanNeural",
        "zh-CN-XiaoruiNeural",
    )
    private val FEMALE_ELDER = listOf(
        "zh-CN-XiaoruiNeural",
        "zh-CN-XiaomoNeural",
        "zh-CN-XiaohanNeural",
    )
    private val FEMALE_COLD = listOf(
        "zh-CN-XiaomoNeural",
        "zh-CN-XiaoruiNeural",
        "zh-CN-XiaohanNeural",
    )

    private val UNKNOWN = (
        MALE_SUPPORTING.take(3) +
            FEMALE_SUPPORTING.take(3)
        ).distinct()

    fun preferredNames(
        cue: ReadAloudPlaybackCue,
        inferredGender: String,
    ): List<String> {
        if (cue.roleType == SpeechRoleType.Narrator) return NARRATOR

        val profile = cue.characterPerformance
        val role = profile?.role.orEmpty().lowercase()
        val age = profile?.voiceAgeBand.orEmpty().lowercase()
        val personality = profile?.personality.orEmpty()

        return when (inferredGender.lowercase()) {
            "male" -> when {
                role == "male_lead" -> MALE_LEAD
                isElder(age) -> MALE_ELDER
                isChildOrYoung(age) -> MALE_YOUNG
                isColdOrVillain(personality) -> MALE_COLD
                isMatureOrCommanding(age, personality) -> MALE_MATURE
                else -> MALE_SUPPORTING
            }
            "female" -> when {
                role == "female_lead" -> FEMALE_LEAD
                isElder(age) -> FEMALE_ELDER
                isChildOrYoung(age) -> FEMALE_YOUNG
                isColdOrVillain(personality) -> FEMALE_COLD
                isMatureOrCommanding(age, personality) -> FEMALE_MATURE
                else -> FEMALE_SUPPORTING
            }
            else -> UNKNOWN
        }
    }

    private fun isChildOrYoung(age: String): Boolean =
        age in setOf(
            "child", "kid", "teen", "young", "youth", "young_adult",
            "儿童", "孩子", "少年", "少女", "青年",
        )

    private fun isElder(age: String): Boolean =
        age in setOf("elder", "old", "elderly", "老人", "老年")

    private fun isColdOrVillain(personality: String): Boolean =
        Regex("反派|阴冷|冷酷|冷血|狠辣|凶狠|邪气|阴沉|淡漠|高冷")
            .containsMatchIn(personality)

    private fun isMatureOrCommanding(age: String, personality: String): Boolean =
        age in setOf("middle", "middle_aged", "mature", "中年", "成熟") ||
            Regex("威严|霸气|强势|果断|首领|领导|上位者|沉稳")
                .containsMatchIn(personality)
}
