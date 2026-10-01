package io.legado.app.domain.model.readaloud

data class SystemTtsVoiceConfig(
    val speechRate: Float? = null,
    val pitch: Float? = null,
    val rateMultiplier: Float? = null,
    val pitchMultiplier: Float? = null,
)
