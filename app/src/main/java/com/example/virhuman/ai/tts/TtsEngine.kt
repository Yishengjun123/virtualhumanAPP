package com.example.virhuman.ai.tts

interface TtsEngine {
    fun speak(text: String): Boolean
    fun isReady(): Boolean
    fun stop()
    fun release()
}
