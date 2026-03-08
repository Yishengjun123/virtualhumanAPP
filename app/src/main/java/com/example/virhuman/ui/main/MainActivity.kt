package com.example.virhuman.ui.main

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.virhuman.BuildConfig
import com.example.virhuman.R
import com.example.virhuman.ai.ali.AiSession
import com.example.virhuman.ai.ali.DashScopeManager
import com.example.virhuman.ai.asr.AsrEngine
import com.example.virhuman.ai.asr.GlobalAsrManager
import com.example.virhuman.ai.tts.SherpaTtsEngine
import com.example.virhuman.ai.tts.TtsEngine
import com.example.virhuman.databinding.ActivityMainBinding
import com.example.virhuman.video.DigitalHumanState
import com.example.virhuman.video.DigitalHumanVideoPlayer
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), AsrEngine.Callback {
    private val tag = "MainVoiceChain"
    private val minSpeakChars = 8
    private lateinit var binding: ActivityMainBinding
    private var asrEngine: AsrEngine? = null
    private var ttsEngine: TtsEngine? = null
    private lateinit var videoPlayer: DigitalHumanVideoPlayer
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentState: DigitalHumanState? = null
    private var isListening = false
    @Volatile private var asrInitializing = false
    @Volatile private var ttsInitializing = false
    @Volatile private var aiRequesting = false
    @Volatile private var spokenCursor = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        videoPlayer = DigitalHumanVideoPlayer(this)
        videoPlayer.bind(binding.playerView)
        switchState(DigitalHumanState.LEISURE)
        DashScopeManager.updateCredentials(BuildConfig.AI_APP_ID, BuildConfig.AI_API_KEY)
        warmupEngines()

        binding.btnStartListen.setOnClickListener {
            if (isListening) {
                asrEngine?.stopListening()
                isListening = false
                binding.btnStartListen.setText(R.string.start_asr)
                if (!aiRequesting) {
                    switchState(DigitalHumanState.LEISURE)
                }
                return@setOnClickListener
            }
            ensureAudioPermission {
                ensureAsrReady { engine ->
                    if (engine == null || !engine.isAvailable()) {
                        binding.tvSubtitle.setText(R.string.asr_not_available)
                        return@ensureAsrReady
                    }
                    binding.tvSubtitle.text = ""
                    switchState(DigitalHumanState.LISTENING)
                    engine.startListening()
                    isListening = true
                    binding.btnStartListen.setText(R.string.stop_asr)
                }
            }
        }

        binding.btnTtsTest.setOnClickListener {
            ensureTtsReady { engine ->
                if (engine == null || !engine.isReady()) {
                    binding.tvSubtitle.setText(R.string.tts_not_ready)
                    return@ensureTtsReady
                }
                val success = engine.speak(getString(R.string.tts_demo_text))
                if (!success) {
                    binding.tvSubtitle.setText(R.string.tts_speak_failed)
                }
            }
        }

        binding.btnDeviceInfo.setOnClickListener {
            val model = Build.MODEL
            val deviceCode = Build.DEVICE
            Log.d(tag, "DeviceInfo model=$model, deviceCode=$deviceCode")
            Toast.makeText(this, "model=$model, device=$deviceCode", Toast.LENGTH_SHORT).show()
        }
    }

    private fun ensureAudioPermission(onGranted: () -> Unit) {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            onGranted()
            return
        }
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1001)
    }

    override fun onPartialResult(text: String) {
        runOnUiThread {
            binding.tvSubtitle.text = text
        }
    }

    override fun onFinalResult(text: String) {
        runOnUiThread {
            binding.tvSubtitle.text = text
            isListening = false
            binding.btnStartListen.setText(R.string.start_asr)
        }
        switchState(DigitalHumanState.LISTENING)
        sendToAi(text)
    }

    override fun onError(message: String) {
        runOnUiThread {
            binding.tvSubtitle.text = getString(R.string.asr_error, message)
            isListening = false
            binding.btnStartListen.setText(R.string.start_asr)
            if (!aiRequesting) {
                switchState(DigitalHumanState.LEISURE)
            }
        }
    }

    override fun onStopped() {
        runOnUiThread {
            isListening = false
            binding.btnStartListen.setText(R.string.start_asr)
            Toast.makeText(this, R.string.asr_stopped_toast, Toast.LENGTH_SHORT).show()
            if (!aiRequesting) {
                switchState(DigitalHumanState.LEISURE)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        DashScopeManager.cancelCurrentStreaming()
        asrEngine?.stopListening()
        GlobalAsrManager.detachCallback()
        asrEngine = null
        videoPlayer.release()
        ttsEngine?.release()
        ttsEngine = null
    }

    private fun sendToAi(userText: String) {
        val prompt = userText.trim()
        if (prompt.isBlank()) return
        if (!DashScopeManager.isConfigured()) {
            runOnUiThread {
                binding.tvSubtitle.setText(R.string.ai_not_configured)
            }
            return
        }
        if (aiRequesting) {
            runOnUiThread {
                Toast.makeText(this, R.string.ai_request_in_progress, Toast.LENGTH_SHORT).show()
            }
            return
        }

        aiRequesting = true
        AiSession.updateSessionId()
        runOnUiThread {
            Toast.makeText(this, R.string.ai_sent_toast, Toast.LENGTH_SHORT).show()
            binding.tvSubtitle.setText(R.string.ai_waiting)
        }
        switchState(DigitalHumanState.LISTENING)

        var mergedText = ""
        spokenCursor = 0
        ensureTtsReady(showHint = false) { engine ->
            engine?.stop()
        }
        DashScopeManager.streamCall(
            prompt = prompt,
            onChunk = { chunk ->
                Log.d(tag, "AI回复(chunk): $chunk")
                mergedText = mergeStreamText(mergedText, chunk)
                Log.d(tag, "AI回复(merged): $mergedText")
                runOnUiThread {
                    binding.tvSubtitle.text = mergedText
                }
                speakReadySentences(mergedText, flushTail = false)
            },
            onDone = {
                aiRequesting = false
                val finalReply = mergedText.trim()
                if (finalReply.isBlank()) {
                    runOnUiThread {
                        binding.tvSubtitle.setText(R.string.ai_empty_reply)
                    }
                    return@streamCall
                }
                runOnUiThread {
                    binding.tvSubtitle.text = finalReply
                }
                Log.d(tag, "AI回复(final): $finalReply")
                speakReadySentences(finalReply, flushTail = true)
                if (finalReply.isBlank()) {
                    switchState(DigitalHumanState.LEISURE)
                }
            },
            onError = { message ->
                aiRequesting = false
                Log.e(tag, "AI请求失败: $message")
                runOnUiThread {
                    binding.tvSubtitle.text = getString(R.string.ai_error, message)
                }
                switchState(DigitalHumanState.LEISURE)
                ensureTtsReady(showHint = false) { engine ->
                    engine?.stop()
                }
            }
        )
    }

    // DashScope stream may return either full text or incremental text; handle both.
    private fun mergeStreamText(previous: String, incoming: String): String {
        val chunk = incoming.trim()
        if (chunk.isEmpty()) return previous
        if (previous.isEmpty()) return chunk
        return if (chunk.startsWith(previous)) chunk else previous + chunk
    }

    private fun speakReadySentences(fullText: String, flushTail: Boolean) {
        val text = fullText.trim()
        if (text.isEmpty()) return
        if (spokenCursor > text.length) {
            spokenCursor = 0
        }
        val end = if (flushTail) {
            text.length
        } else {
            findSpeakBoundary(text, spokenCursor)
        }
        if (end <= spokenCursor) return

        val segment = text.substring(spokenCursor, end).trim()
        spokenCursor = end
        if (segment.isEmpty()) return
        Log.d(tag, "TTS播报(segment): $segment")
        switchState(DigitalHumanState.SPEAKING)
        ensureTtsReady(showHint = false) { engine ->
            engine?.speak(segment)
        }
    }

    private fun findSpeakBoundary(text: String, start: Int): Int {
        for (i in start until text.length) {
            when (text[i]) {
                '\u3002', '\uFF01', '\uFF1F', '!', '?', '\n' -> {
                    val end = i + 1
                    if (end - start >= minSpeakChars) {
                        return end
                    }
                }
            }
        }
        return start
    }

    private fun ensureAsrReady(showHint: Boolean = true, onReady: (AsrEngine?) -> Unit) {
        val existing = asrEngine
        if (existing != null) {
            onReady(existing)
            return
        }
        if (asrInitializing) return
        asrInitializing = true
        if (showHint) binding.tvSubtitle.setText(R.string.asr_initializing)
        thread(start = true, name = "asr-init") {
            val engine = GlobalAsrManager.acquire(applicationContext, this)
            asrEngine = engine
            asrInitializing = false
            runOnUiThread { onReady(engine) }
        }
    }

    private fun ensureTtsReady(showHint: Boolean = true, onReady: (TtsEngine?) -> Unit) {
        val existing = ttsEngine
        if (existing != null) {
            onReady(existing)
            return
        }
        if (ttsInitializing) return
        ttsInitializing = true
        if (showHint) binding.tvSubtitle.setText(R.string.tts_initializing)
        thread(start = true, name = "tts-init") {
            val engine = try {
                SherpaTtsEngine(this)
            } catch (_: Throwable) {
                null
            }
            engine?.setOnIdleListener {
                mainHandler.postDelayed({
                    if (!isListening && !aiRequesting) {
                        switchState(DigitalHumanState.LEISURE)
                    }
                }, 120L)
            }
            ttsEngine = engine
            ttsInitializing = false
            runOnUiThread { onReady(engine) }
        }
    }

    private fun warmupEngines() {
        // Preload in background to reduce first-click latency.
        ensureAsrReady(showHint = false) {}
        ensureTtsReady(showHint = false) {}
    }

    private fun switchState(state: DigitalHumanState) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (state == currentState) return
            currentState = state
            videoPlayer.setState(state)
            return
        }
        mainHandler.post { switchState(state) }
    }
}

