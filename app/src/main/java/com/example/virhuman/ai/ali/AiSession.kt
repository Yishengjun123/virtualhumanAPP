package com.example.virhuman.ai.ali

import java.util.UUID

object AiSession {
    @Volatile
    private var sessionId: String = UUID.randomUUID().toString()

    fun getSessionId(): String = sessionId

    fun updateSessionId() {
        sessionId = UUID.randomUUID().toString()
    }
}
