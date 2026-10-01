package io.legado.app.domain.model.readaloud

data class SpeechTuning(
    val rateMultiplier: Float = 1f,
    val pitchMultiplier: Float = 1f,
)

/**
 * Deterministic V5 final tuning.
 * AI never runs in the audio playback hot path.
 */
object HumanizedSpeechTuning {

    fun forCue(cue: ReadAloudPlaybackCue): SpeechTuning {
        var rate = when (cue.roleType) {
            SpeechRoleType.Narrator -> 0.995f
            SpeechRoleType.Character -> 1.00f
            SpeechRoleType.Thought -> 0.94f
            SpeechRoleType.Unknown -> 1.00f
        }
        var pitch = if (cue.roleType == SpeechRoleType.Thought) 0.985f else 1.00f

        when (cue.emotion.lowercase()) {
            "angry" -> { rate *= 1.04f; pitch *= 0.99f }
            "fearful" -> { rate *= 1.015f; pitch *= 1.025f }
            "sad" -> { rate *= 0.94f; pitch *= 0.985f }
            "disgusted" -> { rate *= 0.97f; pitch *= 0.985f }
            "cheerful" -> { rate *= 1.025f; pitch *= 1.015f }
            "surprised" -> { rate *= 1.03f; pitch *= 1.025f }
            "whispering" -> { rate *= 0.93f; pitch *= 0.975f }
            "calm" -> { rate *= 0.975f; pitch *= 0.995f }
        }

        when (cue.characterPerformance?.voiceAgeBand?.lowercase()) {
            "child", "kid", "儿童", "孩子" -> { rate *= 1.02f; pitch *= 1.035f }
            "elder", "old", "elderly", "老人", "老年" -> { rate *= 0.95f; pitch *= 0.96f }
            "young", "youth", "teen", "young_adult", "青年", "少年" -> {
                rate *= 1.008f
                pitch *= 1.008f
            }
        }

        val personality = cue.characterPerformance?.personality.orEmpty()
        when {
            coldPersonality.containsMatchIn(personality) -> {
                rate *= 0.98f
                pitch *= 0.992f
            }
            livelyPersonality.containsMatchIn(personality) -> {
                rate *= 1.015f
                pitch *= 1.008f
            }
            commandingPersonality.containsMatchIn(personality) -> {
                rate *= 0.975f
                pitch *= 0.985f
            }
        }

        if (cue.text.contains("……") || cue.text.contains("...")) rate *= 0.965f

        return SpeechTuning(
            rateMultiplier = rate.coerceIn(0.89f, 1.07f),
            pitchMultiplier = pitch.coerceIn(0.94f, 1.05f),
        )
    }

    fun inferredGender(cues: List<ReadAloudPlaybackCue>, index: Int): String {
        val cue = cues.getOrNull(index) ?: return ""
        val profile = cue.characterPerformance
        when (profile?.voiceGender?.lowercase()) {
            "female" -> return "female"
            "male" -> return "male"
        }
        when (profile?.role) {
            "female_lead", "female_supporting" -> return "female"
            "male_lead", "male_supporting" -> return "male"
        }

        val context = contextAround(cues, index)
        return when {
            femaleCueRegex.containsMatchIn(context) -> "female"
            maleCueRegex.containsMatchIn(context) -> "male"
            else -> ""
        }
    }

    fun stableSpeakerKey(cues: List<ReadAloudPlaybackCue>, index: Int): String {
        val cue = cues.getOrNull(index) ?: return "unknown-speaker"
        if (cue.roleType == SpeechRoleType.Narrator) return "narrator"

        cue.characterId?.takeIf(String::isNotBlank)?.let { return "character:$it" }
        cue.characterPerformance?.characterId
            ?.takeIf(String::isNotBlank)
            ?.let { return "character:$it" }

        explicitSpeakerName(cues, index)?.let { return "name:$it" }

        val blockStart = dialogueBlockStart(cues, index)
        val blockSeed = cues.getOrNull(blockStart)?.chapterStart ?: cue.chapterStart
        if (cue.roleType == SpeechRoleType.Thought) return "thought:$blockSeed"

        var turnIndex = index
        while (turnIndex > blockStart) {
            val previous = cues.getOrNull(turnIndex - 1) ?: break
            val current = cues.getOrNull(turnIndex) ?: break
            val continuousCharacterRun =
                previous.roleType == SpeechRoleType.Character &&
                    current.roleType == SpeechRoleType.Character &&
                    previous.paragraphIndex == current.paragraphIndex &&
                    previous.chapterEnd == current.chapterStart
            if (!continuousCharacterRun) break
            turnIndex--
        }

        val turn = cues.subList(blockStart, turnIndex + 1).count {
            it.roleType == SpeechRoleType.Character || it.roleType == SpeechRoleType.Thought
        }
        val slot = if ((turn - 1).coerceAtLeast(0) % 2 == 0) "A" else "B"
        val gender = inferredGender(cues, index).ifBlank { "unknown" }
        return "dialogue:$blockSeed:$gender:$slot"
    }

    fun pauseBeforeMs(
        cues: List<ReadAloudPlaybackCue>,
        index: Int,
        baseMs: Long,
    ): Long {
        if (baseMs <= 0L || index <= 0) return 0L
        val previous = cues.getOrNull(index - 1) ?: return baseMs
        val current = cues.getOrNull(index) ?: return baseMs
        val sameSpeaker =
            stableSpeakerKey(cues, index - 1) == stableSpeakerKey(cues, index)

        var value = when {
            sameSpeaker && previous.paragraphIndex == current.paragraphIndex ->
                minOf(baseMs, 20L)
            previous.roleType == SpeechRoleType.Narrator &&
                current.roleType == SpeechRoleType.Narrator ->
                minOf(baseMs, 50L)
            else -> baseMs
        }

        val text = previous.text.trim()
        when {
            text.endsWith("……") || text.endsWith("...") -> value += 90L
            text.endsWith("？") || text.endsWith("?") -> value += 20L
            text.endsWith("。") -> value += 15L
            text.endsWith("！") || text.endsWith("!") -> value -= 10L
        }

        when (previous.emotion.lowercase()) {
            "sad" -> value += 40L
            "whispering" -> value += 30L
            "calm" -> value += 15L
            "angry" -> value -= 15L
        }

        if (previous.roleType == SpeechRoleType.Thought) value += 25L
        if (current.roleType != previous.roleType) value += 15L
        return value.coerceIn(20L, 280L)
    }

    private fun explicitSpeakerName(
        cues: List<ReadAloudPlaybackCue>,
        index: Int,
    ): String? {
        val context = contextAround(cues, index)
        val match = speakerNameRegex.findAll(context).lastOrNull() ?: return null
        val value = match.groupValues[1].trim()
        return value.takeIf { it.length in 2..5 && it !in stopNames }
    }

    private fun contextAround(cues: List<ReadAloudPlaybackCue>, index: Int): String =
        buildList {
            for (distance in 1..2) {
                cues.getOrNull(index - distance)
                    ?.takeIf { it.roleType == SpeechRoleType.Narrator }
                    ?.text
                    ?.let(::add)
                cues.getOrNull(index + distance)
                    ?.takeIf { it.roleType == SpeechRoleType.Narrator }
                    ?.text
                    ?.let(::add)
            }
        }.joinToString(" ")

    private fun dialogueBlockStart(
        cues: List<ReadAloudPlaybackCue>,
        index: Int,
    ): Int {
        var start = index
        var cursor = index - 1
        var inspected = 0
        val paragraph = cues.getOrNull(index)?.paragraphIndex ?: return index
        while (cursor >= 0 && inspected < 12) {
            val cue = cues[cursor]
            if (
                cue.roleType == SpeechRoleType.Narrator &&
                (
                    cue.text.trim().length >= 88 ||
                    sceneBreakRegex.containsMatchIn(cue.text) ||
                    paragraph - cue.paragraphIndex >= 4
                )
            ) break
            start = cursor
            cursor--
            inspected++
        }
        return start
    }

    private val speakerNameRegex = Regex(
        "([\\p{IsHan}·]{2,5})(?:低声|沉声|冷声|怒声|大声|轻声|厉声|笑着|淡淡地|缓缓地|咬牙|喃喃|嘟囔)?(?:说|说道|问|问道|答|答道|喊|喊道|叫|叫道|喝道|笑道|叹道|反问道|开口道)"
    )
    private val femaleCueRegex = Regex(
        "(?:她|女人|女子|女孩|少女|姑娘|小姐|夫人|太太|母亲|妈妈|姐姐|妹妹|奶奶|女声)"
    )
    private val maleCueRegex = Regex(
        "(?:他|男人|男子|男孩|少年|青年|先生|父亲|爸爸|哥哥|弟弟|爷爷|男声)"
    )
    private val sceneBreakRegex = Regex(
        "(?:与此同时|另一边|另一处|片刻之后|许久之后|第二天|翌日|次日|夜幕|天亮|镜头一转|场景一转)"
    )
    private val coldPersonality = Regex("冷酷|冷静|沉稳|寡言|克制|淡漠|高冷")
    private val livelyPersonality = Regex("活泼|开朗|跳脱|调皮|可爱|热情")
    private val commandingPersonality = Regex("威严|霸气|强势|果断|首领|领导|上位者")
    private val stopNames = setOf(
        "这个男人", "那个男人", "这个女人", "那个女人",
        "年轻男人", "年轻女人", "中年男人", "中年女人",
        "一个男人", "一个女人", "所有人", "众人", "对方",
        "男人", "女人", "老人", "老头", "青年", "少年", "少女", "女孩", "男孩",
    )
}
