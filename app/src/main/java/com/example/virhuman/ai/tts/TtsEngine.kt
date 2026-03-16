package com.example.virhuman.ai.tts

interface TtsEngine {
    fun speak(text: String): Boolean
    fun isReady(): Boolean
    fun setOnIdleListener(listener: (() -> Unit)?) {}
    fun setOnSegmentDoneListener(listener: (() -> Unit)?) {}
    fun setSpeechRate(rate: Float) {}
    fun setSpeakerId(speakerId: Int) {}
    fun stop()
    fun release()
}

