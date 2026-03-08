package com.example.virhuman.ai.asr

import android.content.Context

object GlobalAsrManager {
    private val lock = Any()
    private var engine: SherpaAsrEngine? = null

    private val emptyCallback = object : AsrEngine.Callback {
        override fun onPartialResult(text: String) {}
        override fun onFinalResult(text: String) {}
        override fun onError(message: String) {}
    }

    fun ensurePreparedBlocking(context: Context): Boolean {
        synchronized(lock) {
            val existing = engine
            if (existing != null) {
                return existing.isAvailable()
            }
            return try {
                val created = SherpaAsrEngine(context.applicationContext, emptyCallback)
                engine = created
                created.isAvailable()
            } catch (_: Throwable) {
                false
            }
        }
    }

    fun acquire(context: Context, callback: AsrEngine.Callback): AsrEngine? {
        synchronized(lock) {
            if (engine == null) {
                val ok = ensurePreparedBlocking(context)
                if (!ok) return null
            }
            val target = engine ?: return null
            target.updateCallback(callback)
            return target
        }
    }

    fun detachCallback() {
        synchronized(lock) {
            engine?.updateCallback(emptyCallback)
        }
    }
}
