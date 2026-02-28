package com.example.virhuman.ai.asr

interface AsrEngine {
    fun startListening()
    fun stopListening()
    fun release()
    fun isAvailable(): Boolean

    interface Callback {
        fun onPartialResult(text: String)
        fun onFinalResult(text: String)
        fun onError(message: String)
        fun onStopped() {}
    }
}
