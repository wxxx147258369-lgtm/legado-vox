@file:Suppress("DEPRECATION")
package io.legado.app.service

import android.app.PendingIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import androidx.lifecycle.lifecycleScope
import io.legado.app.domain.gateway.ReadAloudSettingsGateway
import io.legado.app.exception.NoStackTraceException
import io.legado.app.domain.model.readaloud.ReadAloudPlaybackCursor
import io.legado.app.domain.model.readaloud.HumanizedSpeechTuning
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.domain.model.readaloud.SpeechEngineRoute
import io.legado.app.domain.model.readaloud.SpeechRoleType
import io.legado.app.domain.model.readaloud.SpeechVoiceRouter
import io.legado.app.domain.model.readaloud.SystemTtsVoiceConfig
import io.legado.app.help.MediaHelp
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.ui.config.readConfig.ReadConfig
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 本地朗读
 */
class TTSReadAloudService : BaseReadAloudService(), KoinComponent {

    override val useSpeechPlaybackQueue: Boolean = true

    private val readAloudSettingsGateway: ReadAloudSettingsGateway by inject()
    @Volatile
    private var speechRateSetting: Int = 8

    private var textToSpeech: TextToSpeech? = null
    private var ttsInitFinish = false
    private val ttsUtteranceListener = TTSUtteranceListener()
    private var speakJob: Coroutine<*>? = null
    private var utteranceStartPos = 0
    private var utteranceStartReadAloudNumber = 0
    private var needParagraphInterval = false // 是否需要进行段落间隔延迟
    private var activeEngine = ""
    private var activeVoiceName = ""
    private var defaultVoiceName = ""
    private var disabledEngine = ""
    private var activeSpeechRate = Float.NaN
    private var activePitch = Float.NaN
    private var initGeneration = 0
    private val TAG = "TTSReadAloudService"

    private companion object {
        const val EDGE_TTS_PACKAGE = "top.initsnow.edge_tts_android"

        val NARRATOR_VOICE_PREFERENCES = listOf(
            "zh-CN-XiaoxiaoNeural",
            "zh-CN-YunxiNeural",
            "zh-CN-XiaoyiNeural",
        )

        val FEMALE_VOICE_PREFERENCES = listOf(
            "zh-CN-XiaoyiNeural",
            "zh-CN-XiaoxiaoNeural",
            "zh-CN-XiaohanNeural",
            "zh-CN-XiaomengNeural",
            "zh-CN-XiaomoNeural",
            "zh-CN-XiaoruiNeural",
            "zh-CN-XiaoxuanNeural",
            "zh-CN-XiaoyanNeural",
            "zh-CN-XiaozhenNeural",
        )

        val MALE_VOICE_PREFERENCES = listOf(
            "zh-CN-YunxiNeural",
            "zh-CN-YunyangNeural",
            "zh-CN-YunjianNeural",
            "zh-CN-YunfengNeural",
            "zh-CN-YunhaoNeural",
            "zh-CN-YunyeNeural",
            "zh-CN-YunzeNeural",
        )
    }

    override fun onCreate() {
        super.onCreate()
        speechRateSetting = readAloudSettingsGateway.currentSettings.ttsSpeechRate
        lifecycleScope.launch {
            readAloudSettingsGateway.settings.collect {
                speechRateSetting = it.ttsSpeechRate
            }
        }
        initTts()
    }

    override fun onDestroy() {
        super.onDestroy()
        clearTTS()
    }

    @Synchronized
    private fun initTts(engineOverride: String? = null) {
        ttsInitFinish = false
        val engine = engineOverride
            ?: GSON.fromJsonObject<SelectItem<String>>(ReadAloud.ttsEngine).getOrNull()?.value
        activeEngine = engine.orEmpty()
        val generation = ++initGeneration
        LogUtils.d(TAG, "initTts engine:$engine")
        textToSpeech = if (engine.isNullOrBlank()) {
            TextToSpeech(this) { status -> onTtsInitialized(status, generation) }
        } else {
            TextToSpeech(this, { status -> onTtsInitialized(status, generation) }, engine)
        }
        upSpeechRate()
    }

    @Synchronized
    fun clearTTS() {
        textToSpeech?.runCatching {
            stop()
            shutdown()
        }
        textToSpeech = null
        ttsInitFinish = false
        activeVoiceName = ""
        defaultVoiceName = ""
        activeSpeechRate = Float.NaN
        activePitch = Float.NaN
        initGeneration++
    }

    private fun onTtsInitialized(status: Int, generation: Int) {
        if (generation != initGeneration) return
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.let {
                it.setOnUtteranceProgressListener(ttsUtteranceListener)
                defaultVoiceName = it.defaultVoice?.name.orEmpty()
                activeVoiceName = defaultVoiceName
                ttsInitFinish = true
                play()
            }
            return
        }

        val failedEngine = activeEngine
        if (
            failedEngine.isNotBlank() &&
            !failedEngine.equals(disabledEngine, ignoreCase = true)
        ) {
            disabledEngine = failedEngine
            AppLog.putDebug("TTS引擎初始化失败，当前会话回退系统TTS: $failedEngine")
            clearTTS()
            initTts("")
        } else {
            toastOnUi(R.string.tts_init_failed)
        }
    }

    @Synchronized
    override fun play() {
        if (hasSpeechPlaybackQueue) {
            val route = systemVoiceForCurrentCue()
            val requiredEngine = route.engineId
            if (requiredEngine != activeEngine || textToSpeech == null) {
                clearTTS()
                initTts(requiredEngine)
                return
            }
            applyVoice(route.speakerId)
            applyPreset(route)
        }
        if (!ttsInitFinish) return
        if (!requestFocus()) return
        if (contentList.isEmpty()) {
            AppLog.putDebug("朗读列表为空")
            ReadBook.readAloud()
            return
        }
        super.play()
        MediaHelp.playSilentSound(this@TTSReadAloudService)
        
        // 捕获本次是否需要进行段落延迟，并将标志位复位（防多次触发）
        val isDelay = needParagraphInterval
        needParagraphInterval = false
        
        speakJob?.cancel()
        speakJob = execute {
            val interval = dynamicCueIntervalMs()
            AppLog.putDebug("TTS_PLAY: nowSpeak=$nowSpeak, isDelay=$isDelay, interval=$interval")
            
            if (hasSpeechPlaybackQueue || interval > 0) {
                // 段落间隔模式：单段播放
                if (isDelay) {
                    AppLog.putDebug("TTS开始延迟: $interval 毫秒")
                    delay(interval)
                    AppLog.putDebug("TTS延迟结束，准备播放")
                }
                ensureActive()
                
                LogUtils.d(TAG, "朗读列表大小 ${contentList.size}")
                val tts = textToSpeech ?: throw NoStackTraceException("tts is null")
                var text = contentList[nowSpeak]
                if (paragraphStartPos > 0) {
                    text = text.substring(paragraphStartPos)
                }
                if (
                    text.matches(AppPattern.notReadAloudRegex) ||
                    shouldSkipTtsNoise(text)
                ) {
                    AppLog.putDebug("TTS段落噪声/全标点跳过: nowSpeak=$nowSpeak")
                    ttsUtteranceListener.onDone(AppConst.APP_TAG + nowSpeak)
                    return@execute
                }
                AppLog.putDebug("TTS开始Speak: $text")
                val result = tts.runCatching {
                    speak(text, TextToSpeech.QUEUE_FLUSH, null, AppConst.APP_TAG + nowSpeak)
                }.getOrElse {
                    AppLog.put("tts出错\n${it.localizedMessage}", it, true)
                    TextToSpeech.ERROR
                }
                if (result == TextToSpeech.ERROR) {
                    AppLog.put("tts出错 尝试重新初始化")
                    clearTTS()
                    initTts()
                    return@execute
                }
                LogUtils.d(TAG, "朗读内容添加完成")
            } else {
                // 无间隔模式：保持原有的队列式连续播放，确保无缝衔接
                LogUtils.d(TAG, "朗读列表大小 ${contentList.size}")
                LogUtils.d(TAG, "朗读页数 ${textChapter?.pageSize}")
                val tts = textToSpeech ?: throw NoStackTraceException("tts is null")
                val contentList = contentList
                var isAddedText = false
                for (i in nowSpeak until contentList.size) {
                    ensureActive()
                    var text = contentList[i]
                    if (paragraphStartPos > 0 && i == nowSpeak) {
                        text = text.substring(paragraphStartPos)
                    }
                    if (text.matches(AppPattern.notReadAloudRegex)) {
                        continue
                    }
                    if (!isAddedText) {
                        val result = tts.runCatching {
                            speak(text, TextToSpeech.QUEUE_FLUSH, null, AppConst.APP_TAG + i)
                        }.getOrElse {
                            AppLog.put("tts出错\n${it.localizedMessage}", it, true)
                            TextToSpeech.ERROR
                        }
                        if (result == TextToSpeech.ERROR) {
                            AppLog.put("tts出错 尝试重新初始化")
                            clearTTS()
                            initTts()
                            return@execute
                        }
                    } else {
                        val result = tts.runCatching {
                            speak(text, TextToSpeech.QUEUE_ADD, null, AppConst.APP_TAG + i)
                        }.getOrElse {
                            AppLog.put("tts出错\n${it.localizedMessage}", it, true)
                            TextToSpeech.ERROR
                        }
                        if (result == TextToSpeech.ERROR) {
                            AppLog.put("tts朗读出错:$text")
                        }
                    }
                    isAddedText = true
                }
                LogUtils.d(TAG, "朗读内容添加完成")
                if (!isAddedText) {
                    playStop()
                    delay(1000)
                    nextChapter()
                }
            }
        }.onError {
            AppLog.putDebug("TTS协程异常: ${it.localizedMessage}")
        }
    }

    private fun systemVoiceForCurrentCue(): ReadAloudVoice {
        val configuredValue = GSON.fromJsonObject<SelectItem<String>>(ReadAloud.ttsEngine)
            .getOrNull()?.value.orEmpty()
        val configured = preferredMultiSpeakerEngine(configuredValue)
        val fallback = ReadAloudVoice(
            id = "runtime-system:$configured",
            engineType = ReadAloudVoice.ENGINE_SYSTEM,
            engineId = configured,
            speakerId = "",
            displayName = configured,
        )
        val cue = playbackQueue.cues.getOrNull(nowSpeak) ?: return fallback
        val routed = SpeechVoiceRouter.route(
            cue = cue,
            supportedEngineTypes = setOf(ReadAloudVoice.ENGINE_SYSTEM),
            defaultRoute = SpeechEngineRoute(ReadAloudVoice.ENGINE_SYSTEM, configured),
        ).voice ?: fallback
        val routedEngine = routed.engineId
            .takeUnless { it.equals(disabledEngine, ignoreCase = true) }
            .orEmpty()
        return when {
            routedEngine.isNotBlank() -> routed.copy(engineId = routedEngine)
            configured.isNotBlank() -> routed.copy(engineId = configured)
            else -> routed.copy(engineId = "")
        }
    }

    /**
     * Zero-config multi-speaker mode:
     * if no concrete system engine was chosen, automatically prefer Edge TTS when installed.
     * Explicit user engine/voice choices still win.
     */
    private fun preferredMultiSpeakerEngine(configured: String): String {
        if (!ReadConfig.useMultiSpeaker) return configured
        if (
            configured.isNotBlank() &&
            !configured.equals(disabledEngine, ignoreCase = true)
        ) return configured

        val engines = textToSpeech?.engines.orEmpty()
            .filterNot { it.name.equals(disabledEngine, ignoreCase = true) }
        return engines.firstOrNull {
            it.name.equals(EDGE_TTS_PACKAGE, ignoreCase = true)
        }?.name ?: engines.firstOrNull {
            it.name.contains("edge_tts", ignoreCase = true) ||
                it.label.toString().contains("Edge TTS", ignoreCase = true)
        }?.name.orEmpty()
    }

    private fun applyVoice(voiceName: String) {
        val requestedName = voiceName.ifBlank {
            if (ReadConfig.useMultiSpeaker) {
                autoVoiceNameForCurrentCue().ifBlank { defaultVoiceName }
            } else {
                defaultVoiceName
            }
        }
        if (requestedName == activeVoiceName) return
        val tts = textToSpeech ?: return
        val voice = tts.voices.orEmpty().firstOrNull { it.name == requestedName }
        if (voice == null) {
            AppLog.putDebug("系统 TTS 音色不可用: $requestedName")
            return
        }
        if (tts.setVoice(voice) == TextToSpeech.SUCCESS) {
            activeVoiceName = requestedName
            AppLog.putDebug("多人朗读自动音色: $requestedName")
        } else {
            AppLog.putDebug("系统 TTS 音色切换失败: $requestedName")
        }
    }

    /**
     * Automatically chooses Chinese voices per cue.
     * Narration stays stable; characters are deterministically spread across voices.
     * If character metadata has a gender, male/female pools are preferred.
     */
    private fun autoVoiceNameForCurrentCue(): String {
        val tts = textToSpeech ?: return ""
        val cueIndex = playbackCursor?.cueIndex ?: nowSpeak
        val cue = playbackQueue.cues.getOrNull(cueIndex) ?: return ""
        val allVoices = tts.voices.orEmpty()
        if (allVoices.isEmpty()) return ""

        val chineseVoices = allVoices.filter { voice ->
            voice.locale.language.equals("zh", ignoreCase = true) ||
                voice.locale.toLanguageTag().startsWith("zh", ignoreCase = true) ||
                voice.name.startsWith("zh-", ignoreCase = true)
        }
        val available = if (chineseVoices.isNotEmpty()) chineseVoices else allVoices.toList()

        fun preferredPool(preferredNames: List<String>): List<android.speech.tts.Voice> {
            val preferred = preferredNames.mapNotNull { preferredName ->
                available.firstOrNull { voice ->
                    voice.name.equals(preferredName, ignoreCase = true) ||
                        voice.name.contains(preferredName, ignoreCase = true)
                }
            }.distinctBy { it.name }
            return if (preferred.isNotEmpty()) preferred else available.sortedBy { it.name }
        }

        val narratorPool = preferredPool(NARRATOR_VOICE_PREFERENCES)
        val femalePool = preferredPool(FEMALE_VOICE_PREFERENCES)
        val malePool = preferredPool(MALE_VOICE_PREFERENCES)

        if (cue.roleType == SpeechRoleType.Narrator) {
            return narratorPool.firstOrNull()?.name.orEmpty()
        }

        val gender = HumanizedSpeechTuning.inferredGender(playbackQueue.cues, cueIndex)
        val pool = when (gender) {
            "female" -> femalePool
            "male" -> malePool
            else -> {
                val narratorName = narratorPool.firstOrNull()?.name
                available.filterNot { it.name == narratorName }
                    .ifEmpty { available }
                    .sortedBy { it.name }
            }
        }
        if (pool.isEmpty()) return narratorPool.firstOrNull()?.name.orEmpty()

        val key = HumanizedSpeechTuning.stableSpeakerKey(playbackQueue.cues, cueIndex)
        return pool[Math.floorMod(key.hashCode(), pool.size)].name
    }

    private fun applyPreset(voice: ReadAloudVoice) {
        val config = runCatching {
            GSON.fromJson(voice.traitsJson, SystemTtsVoiceConfig::class.java)
        }.getOrNull() ?: SystemTtsVoiceConfig()
        val globalRate = if (ReadConfig.ttsFollowSys) {
            1f
        } else {
            (speechRateSetting + 5) / 10f
        }
        val cueIndex = playbackCursor?.cueIndex ?: nowSpeak
        val tuning = playbackQueue.cues.getOrNull(cueIndex)
            ?.let(HumanizedSpeechTuning::forCue)
        val finalRate = (
            (config.speechRate ?: globalRate) *
                (config.rateMultiplier ?: 1f) *
                (tuning?.rateMultiplier ?: 1f)
            ).coerceIn(0.78f, 2.10f)
        val finalPitch = (
            (config.pitch ?: 1f) *
                (config.pitchMultiplier ?: 1f) *
                (tuning?.pitchMultiplier ?: 1f)
            ).coerceIn(0.86f, 1.14f)

        textToSpeech?.apply {
            if (activeSpeechRate.isNaN() || kotlin.math.abs(activeSpeechRate - finalRate) >= 0.015f) {
                setSpeechRate(finalRate)
                activeSpeechRate = finalRate
            }
            if (activePitch.isNaN() || kotlin.math.abs(activePitch - finalPitch) >= 0.015f) {
                setPitch(finalPitch)
                activePitch = finalPitch
            }
        }
    }

    private fun dynamicCueIntervalMs(): Long {
        val base = ReadConfig.ttsParagraphInterval.toLong().coerceAtLeast(0L)
        if (!hasSpeechPlaybackQueue || base <= 0L) return base
        val cueIndex = playbackCursor?.cueIndex ?: nowSpeak
        return HumanizedSpeechTuning.pauseBeforeMs(playbackQueue.cues, cueIndex, base)
    }

    private fun shouldSkipTtsNoise(text: String): Boolean {
        val value = text.trim()
        if (value.length > 100) return false
        val cueIndex = playbackCursor?.cueIndex ?: nowSpeak
        val cue = playbackQueue.cues.getOrNull(cueIndex)
        if (cue != null && cue.roleType != SpeechRoleType.Narrator) return false
        return value.matches(
            Regex("^(?:本章未完.*|.*最新网址.*|.*请收藏本站.*|.*手机用户请浏览.*|.*关注公众号.*|.*章节报错.*|.*加入书签.*)$")
        )
    }

    override fun playStop() {
        textToSpeech?.runCatching {
            stop()
        }
    }

    /**
     * 更新朗读速度
     */
    override fun upSpeechRate(reset: Boolean) {
        if (ReadConfig.ttsFollowSys) {
            if (reset) {
                clearTTS()
                initTts()
            }
        } else {
            val speechRate = (speechRateSetting + 5) / 10f
            textToSpeech?.setSpeechRate(speechRate)
            activeSpeechRate = speechRate
            if (reset && !pause) {
                play()
            }
        }
    }

    /**
     * 暂停朗读
     */
    override fun pauseReadAloud(abandonFocus: Boolean) {
        super.pauseReadAloud(abandonFocus)
        speakJob?.cancel()
        textToSpeech?.runCatching {
            stop()
        }
    }

    /**
     * 恢复朗读
     */
    override fun resumeReadAloud() {
        super.resumeReadAloud()
        play()
    }

    /**
     * 朗读监听
     */
    private inner class TTSUtteranceListener : UtteranceProgressListener() {

        private val TAG = "TTSUtteranceListener"

        override fun onStart(s: String) {
            if (!isCurrentUtterance(s)) return
            LogUtils.d(TAG, "onStart nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$s")
            utteranceStartPos = paragraphStartPos
            utteranceStartReadAloudNumber = readAloudNumber
            textChapter?.let {
                if (contentList[nowSpeak].matches(AppPattern.notReadAloudRegex)) {
                    nextParagraph()
                }
                if (pageIndex + 1 < it.pageSize
                    && readAloudNumber + 1 > it.getReadLength(pageIndex + 1)
                ) {
                    pageIndex++
                    ReadBook.moveToNextPage()
                }
                upTtsProgress(readAloudNumber + 1)
                upMediaMetadata(showContent = true)
            }
        }

        override fun onDone(s: String) {
            if (!isCurrentUtterance(s)) return
            LogUtils.d(TAG, "onDone utteranceId:$s")
            nextParagraph()
            if (!pause && (hasSpeechPlaybackQueue || ReadConfig.ttsParagraphInterval > 0)) {
                needParagraphInterval = ReadConfig.ttsParagraphInterval > 0
                play()
            }
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            super.onRangeStart(utteranceId, start, end, frame)
            if (!isCurrentUtterance(utteranceId)) return
            paragraphStartPos = utteranceStartPos + start
            readAloudNumber = currentRangePosition(utteranceStartReadAloudNumber, start)
            updateReadAloudProgressSnapshot(readAloudNumber + 1)
            val msg =
                "onRangeStart nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$utteranceId start:$start end:$end frame:$frame"
            LogUtils.d(TAG, msg)
            if (moveToReadAloudPage(readAloudNumber)) {
                upTtsProgress(readAloudNumber + 1)
            }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            if (!isCurrentUtterance(utteranceId)) return
            LogUtils.d(
                TAG,
                "onError nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$utteranceId errorCode:$errorCode"
            )
            nextParagraph()
            if (!pause && (hasSpeechPlaybackQueue || ReadConfig.ttsParagraphInterval > 0)) {
                needParagraphInterval = ReadConfig.ttsParagraphInterval > 0
                play()
            }
        }

        private fun nextParagraph() {
            if (hasSpeechPlaybackQueue) {
                val current = playbackCursor
                    ?: ReadAloudPlaybackCursor(nowSpeak, paragraphStartPos)
                playbackQueue.next(current)?.let(::moveToPlaybackCursor) ?: nextChapter()
                return
            }
            //跳过全标点段落
            do {
                readAloudNumber = nextParagraphPosition(
                    currentPosition = readAloudNumber,
                    paragraphLength = contentList[nowSpeak].length,
                    paragraphStartPosition = paragraphStartPos,
                )
                paragraphStartPos = 0
                nowSpeak++
                if (nowSpeak >= contentList.size) {
                    nextChapter()
                    return
                }
            } while (contentList[nowSpeak].matches(AppPattern.notReadAloudRegex))
        }

        @Deprecated("Deprecated in Java")
        override fun onError(s: String) {
            if (!isCurrentUtterance(s)) return
            LogUtils.d(TAG, "onError nowSpeak:$nowSpeak pageIndex:$pageIndex s:$s")
            nextParagraph()
            if (!pause && (hasSpeechPlaybackQueue || ReadConfig.ttsParagraphInterval > 0)) {
                needParagraphInterval = ReadConfig.ttsParagraphInterval > 0
                play()
            }
        }

        private fun isCurrentUtterance(utteranceId: String?): Boolean =
            !hasSpeechPlaybackQueue || utteranceId == AppConst.APP_TAG + nowSpeak

    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<TTSReadAloudService>(actionStr)
    }

}

internal fun nextParagraphPosition(
    currentPosition: Int,
    paragraphLength: Int,
    paragraphStartPosition: Int,
): Int = currentPosition + paragraphLength + 1 - paragraphStartPosition

internal fun currentRangePosition(
    utteranceStartPosition: Int,
    rangeStart: Int,
): Int = utteranceStartPosition + rangeStart
