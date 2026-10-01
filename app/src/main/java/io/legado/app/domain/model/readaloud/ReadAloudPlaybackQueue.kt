package io.legado.app.domain.model.readaloud

data class ReadAloudPlaybackCue(
    val text: String,
    val chapterStart: Int,
    val chapterEnd: Int,
    val paragraphIndex: Int,
    val voice: ReadAloudVoice?,
    val fallbackVoices: List<ReadAloudVoice>,
    val roleType: SpeechRoleType,
    val characterId: String?,
    val emotion: String = "",
    val characterPerformance: CharacterPerformanceProfile? = null,
) {
    init {
        require(chapterStart >= 0)
        require(chapterEnd == chapterStart + text.length)
    }
}

data class ReadAloudPlaybackCursor(
    val cueIndex: Int,
    val offset: Int,
)

data class ReadAloudPlaybackInfo(
    val chapterPosition: Int = 0,
    val chapterLength: Int = 0,
    val text: String = "",
    val engineName: String = "",
    val characterName: String = "",
    val roleType: SpeechRoleType = SpeechRoleType.Narrator,
)

/** Position-based playback queue independent from reader pagination. */
class ReadAloudPlaybackQueue private constructor(
    val cues: List<ReadAloudPlaybackCue>,
) {

    val isEmpty: Boolean get() = cues.isEmpty()

    fun cursorAt(chapterPosition: Int): ReadAloudPlaybackCursor? {
        if (cues.isEmpty()) return null
        val position = chapterPosition.coerceAtLeast(0)
        val containingIndex = cues.binarySearch { cue ->
            when {
                cue.chapterEnd <= position -> -1
                cue.chapterStart > position -> 1
                else -> 0
            }
        }
        val index = if (containingIndex >= 0) {
            containingIndex
        } else {
            (-containingIndex - 1).coerceAtMost(cues.lastIndex)
        }
        val cue = cues[index]
        return ReadAloudPlaybackCursor(
            cueIndex = index,
            offset = (position - cue.chapterStart).coerceIn(0, cue.text.length),
        )
    }

    fun previous(cursor: ReadAloudPlaybackCursor): ReadAloudPlaybackCursor? =
        (cursor.cueIndex - 1).takeIf { it in cues.indices }
            ?.let { ReadAloudPlaybackCursor(it, 0) }

    fun next(cursor: ReadAloudPlaybackCursor): ReadAloudPlaybackCursor? =
        (cursor.cueIndex + 1).takeIf { it in cues.indices }
            ?.let { ReadAloudPlaybackCursor(it, 0) }

    companion object {
        val Empty = ReadAloudPlaybackQueue(emptyList())

        fun from(plan: List<SpeechPlanItem>, smoothMode: Boolean = false): ReadAloudPlaybackQueue {
            if (plan.isEmpty()) return Empty
            val rawCues = plan.map { item ->
                val segment = item.segment
                ReadAloudPlaybackCue(
                    text = segment.text,
                    chapterStart = segment.chapterPosition,
                    chapterEnd = segment.chapterPosition + segment.text.length,
                    paragraphIndex = segment.paragraphIndex,
                    voice = item.voice,
                    fallbackVoices = item.fallbackVoices,
                    roleType = segment.roleType,
                    characterId = segment.characterId,
                    emotion = segment.emotion,
                    characterPerformance = item.characterPerformance,
                )
            }.sortedWith(compareBy(ReadAloudPlaybackCue::chapterStart, ReadAloudPlaybackCue::chapterEnd))
            require(rawCues.zipWithNext().none { (left, right) -> left.chapterEnd > right.chapterStart }) {
                "Playback cues must not overlap"
            }

            val cues = buildList {
                rawCues.forEach { cue ->
                    val previous = lastOrNull()
                    if (previous != null && canMerge(previous, cue, smoothMode)) {
                        removeAt(lastIndex)
                        add(
                            previous.copy(
                                text = previous.text + cue.text,
                                chapterEnd = cue.chapterEnd,
                            )
                        )
                    } else {
                        add(cue)
                    }
                }
            }
            return ReadAloudPlaybackQueue(cues)
        }

        private fun canMerge(
            left: ReadAloudPlaybackCue,
            right: ReadAloudPlaybackCue,
            smoothMode: Boolean,
        ): Boolean {
            if (left.chapterEnd != right.chapterStart) return false

            if (smoothMode) {
                if (left.text.length + right.text.length > 320) return false
                return smoothVoiceClass(left) == smoothVoiceClass(right)
            }

            if (left.paragraphIndex != right.paragraphIndex) return false
            if (left.roleType != right.roleType) return false
            if (left.emotion != right.emotion) return false
            if (!sameVoice(left.voice, right.voice)) return false

            return when (left.roleType) {
                SpeechRoleType.Narrator -> true
                SpeechRoleType.Character,
                SpeechRoleType.Thought -> {
                    val leftId = left.characterId ?: left.characterPerformance?.characterId
                    val rightId = right.characterId ?: right.characterPerformance?.characterId
                    leftId != null && leftId == rightId
                }
                SpeechRoleType.Unknown -> false
            }
        }

        private fun smoothVoiceClass(cue: ReadAloudPlaybackCue): String {
            if (cue.roleType == SpeechRoleType.Narrator) return "narrator"
            val profile = cue.characterPerformance
            return when {
                profile?.voiceGender.equals("female", ignoreCase = true) -> "female"
                profile?.role in setOf("female_lead", "female_supporting") -> "female"
                else -> "male"
            }
        }

        private fun sameVoice(
            left: ReadAloudVoice?,
            right: ReadAloudVoice?,
        ): Boolean {
            if (left == null || right == null) return left == right
            return left.engineType == right.engineType &&
                left.engineId == right.engineId &&
                left.speakerId == right.speakerId
        }
    }
}
