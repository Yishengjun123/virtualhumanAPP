package com.example.virhuman.ai.ali

import com.alibaba.dashscope.app.Application
import com.alibaba.dashscope.app.ApplicationParam
import com.alibaba.dashscope.app.ApplicationResult
import io.reactivex.Flowable
import kotlin.concurrent.thread

object DashScopeManager {
    @Volatile
    private var appId: String = ""

    @Volatile
    private var apiKey: String = ""

    @Volatile
    private var currentWorker: Thread? = null

    fun updateCredentials(appId: String, apiKey: String) {
        this.appId = appId.trim()
        this.apiKey = apiKey.trim()
    }

    fun isConfigured(): Boolean = appId.isNotBlank() && apiKey.isNotBlank()

    fun streamCall(
        prompt: String,
        onChunk: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        cancelCurrentStreaming()
        currentWorker = thread(start = true, name = "dashscope-stream") {
            try {
                if (!isConfigured()) {
                    onError("DashScope appId/apiKey not configured")
                    return@thread
                }
                val param = ApplicationParam.builder()
                    .apiKey(apiKey)
                    .appId(appId)
                    .prompt(prompt)
                    .sessionId(AiSession.getSessionId())
                    .incrementalOutput(true)
                    .build()

                val application = Application()
                val flowable: Flowable<ApplicationResult> = application.streamCall(param)
                val iterable = flowable.blockingIterable()
                for (result in iterable) {
                    if (Thread.currentThread().isInterrupted) {
                        return@thread
                    }
                    val text = result.output?.text.orEmpty()
                    if (text.isNotBlank()) {
                        onChunk(text)
                    }
                }
                if (!Thread.currentThread().isInterrupted) {
                    onDone()
                }
            } catch (e: Exception) {
                if (!Thread.currentThread().isInterrupted) {
                    onError(e.message ?: "DashScope request failed")
                }
            }
        }
    }

    fun cancelCurrentStreaming() {
        currentWorker?.interrupt()
        currentWorker = null
    }
}
