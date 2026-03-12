package com.example.virhuman.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
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
import com.example.virhuman.data.MMKVHelper
import com.example.virhuman.databinding.ActivityMainBinding
import com.example.virhuman.ui.settings.SettingsActivity
import com.example.virhuman.util.WifiMonitor
import com.example.virhuman.video.DigitalHumanState
import com.example.virhuman.video.DigitalHumanVideoPlayer
import com.example.virhuman.vision.FaceDetectionController
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), AsrEngine.Callback {
    private val tag = "MainVoiceChain"
    private val faceTag = "FaceDetect"
    private val minSpeakChars = 8
    private lateinit var binding: ActivityMainBinding
    private var asrEngine: AsrEngine? = null
    private var ttsEngine: TtsEngine? = null
    private lateinit var videoPlayer: DigitalHumanVideoPlayer
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentState: DigitalHumanState? = null
    private var isListening = false

    @Volatile
    private var asrInitializing = false

    @Volatile
    private var ttsInitializing = false

    @Volatile
    private var aiRequesting = false

    @Volatile
    private var isFinalizingToAi = false

    @Volatile
    private var awaitingAsrFinal = false

    @Volatile
    private var spokenCursor = 0
    @Volatile
    private var lastTtsRequestAt = 0L


    private var aiBubbleView: TextView? = null
    private var userBubbleView: TextView? = null

    private var faceController: FaceDetectionController? = null
    private var hasUserDetected = false
    private var faceDetectedStartTime = 0L
    private var lastFaceDetected = false
    private val faceStableMs = 1000L
    private val asrReadyCallbacks = mutableListOf<(AsrEngine?) -> Unit>()
    private val ttsReadyCallbacks = mutableListOf<(TtsEngine?) -> Unit>()


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        videoPlayer = DigitalHumanVideoPlayer(this)
        videoPlayer.bind(binding.playerViewFront, binding.playerViewBack)
        switchState(DigitalHumanState.LEISURE)
        DashScopeManager.updateCredentials(BuildConfig.AI_APP_ID, BuildConfig.AI_API_KEY)
        warmupEngines()

        WifiMonitor.init(this) { status, wifiName, downloadSpeed, uploadSpeed ->
            runOnUiThread {
                binding.tvNetStatus.text = status
                binding.tvNetWifi.text = wifiName
                binding.tvNetDownload.text = downloadSpeed
                binding.tvNetUpload.text = uploadSpeed
            }
        }

        faceController = FaceDetectionController(this) { detectedNow ->
            handleFaceDetectionResult(detectedNow)
        }

        binding.btnStartListen.setOnClickListener {
            if (isListening) {
                awaitingAsrFinal = false
                asrEngine?.stopListening()
                isListening = false
                isFinalizingToAi = false
                binding.btnStartListen.setText(R.string.start_asr_short)
                if (!aiRequesting) {
                    switchState(DigitalHumanState.LEISURE)
                }
                return@setOnClickListener
            }
            ensureAudioPermission {
                ensureAsrReady { engine ->
                    if (engine == null || !engine.isAvailable()) {
                        Toast.makeText(this, R.string.asr_not_available, Toast.LENGTH_SHORT).show()
                        return@ensureAsrReady
                    }
                    prepareLiveUserBubble()
                    switchState(DigitalHumanState.LISTENING)
                    isFinalizingToAi = false
                    awaitingAsrFinal = true
                    engine.startListening()
                    isListening = true
                    binding.btnStartListen.setText(R.string.stop_asr_short)
                }
            }
        }

        binding.btnTtsTest.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnDeviceInfo.setOnClickListener {
            val model = Build.MODEL
            val deviceCode = Build.DEVICE
            Log.d(tag, "DeviceInfo model=$model, deviceCode=$deviceCode")
            Toast.makeText(this, "model=$model, device=$deviceCode", Toast.LENGTH_SHORT).show()
        }
    }

    private fun ensureAudioPermission(onGranted: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            onGranted()
            return
        }
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1001)
    }

    override fun onPartialResult(text: String) {
        runOnUiThread {
            updateLiveUserBubble(text)
        }
    }

    override fun onFinalResult(text: String) {
        awaitingAsrFinal = false
        isFinalizingToAi = true
        runOnUiThread {
            updateLiveUserBubble(text)
            userBubbleView = null
            isListening = false
            binding.btnStartListen.setText(R.string.start_asr_short)
        }
        switchState(DigitalHumanState.LISTENING)
        sendToAi(text)
    }

    override fun onError(message: String) {
        awaitingAsrFinal = false
        isFinalizingToAi = false
        runOnUiThread {
            userBubbleView = null
            Toast.makeText(this, getString(R.string.asr_error, message), Toast.LENGTH_SHORT).show()
            isListening = false
            binding.btnStartListen.setText(R.string.start_asr_short)
            if (!aiRequesting && !isFinalizingToAi) {
                switchState(DigitalHumanState.LEISURE)
            }
        }
    }

    override fun onStopped() {
        runOnUiThread {
            isListening = false
            binding.btnStartListen.setText(R.string.start_asr_short)
            Toast.makeText(this, R.string.asr_stopped_toast, Toast.LENGTH_SHORT).show()
            if (!aiRequesting && !isFinalizingToAi && !awaitingAsrFinal) {
                switchState(DigitalHumanState.LEISURE)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        WifiMonitor.startMonitoring()
        syncFaceDetectionState()
    }

    override fun onPause() {
        super.onPause()
        WifiMonitor.stopMonitoring()
        stopFaceDetection()
    }

    override fun onDestroy() {
        super.onDestroy()
        WifiMonitor.stopMonitoring()
        faceController?.release()
        faceController = null
        awaitingAsrFinal = false
        isFinalizingToAi = false
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
                Toast.makeText(this, R.string.ai_not_configured, Toast.LENGTH_SHORT).show()
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
        }
        switchState(DigitalHumanState.LISTENING)

        var mergedText = ""
        aiBubbleView = null
        spokenCursor = 0
        ensureTtsReady(showHint = false) { engine -> engine?.stop() }

        DashScopeManager.streamCall(
            prompt = prompt,
            onChunk = { chunk ->
                Log.d(tag, "AI鍥炲(chunk): $chunk")
                mergedText = mergeStreamText(mergedText, chunk)
                Log.d(tag, "AI鍥炲(merged): $mergedText")
                runOnUiThread { updateAiBubble(mergedText) }
                speakReadySentences(mergedText, flushTail = false)
            },
            onDone = {
                aiRequesting = false
                val finalReply = mergedText.trim()
                if (finalReply.isBlank()) {
                    isFinalizingToAi = false
                    runOnUiThread {
                        Toast.makeText(this, R.string.ai_empty_reply, Toast.LENGTH_SHORT).show()
                    }
                    return@streamCall
                }
                runOnUiThread { updateAiBubble(finalReply) }
                Log.d(tag, "AI鍥炲(final): $finalReply")
                speakReadySentences(finalReply, flushTail = true)
                isFinalizingToAi = false
            },
            onError = { message ->
                aiRequesting = false
                isFinalizingToAi = false
                Log.e(tag, "AI璇锋眰澶辫触: $message")
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.ai_error, message), Toast.LENGTH_SHORT).show()
                }
                switchState(DigitalHumanState.LEISURE)
                ensureTtsReady(showHint = false) { engine -> engine?.stop() }
            }
        )
    }

    private fun mergeStreamText(previous: String, incoming: String): String {
        val chunk = incoming.trim()
        if (chunk.isEmpty()) return previous
        if (previous.isEmpty()) return chunk
        return if (chunk.startsWith(previous)) chunk else previous + chunk
    }

    private fun speakReadySentences(fullText: String, flushTail: Boolean) {
        val text = fullText.trim()
        if (text.isEmpty()) return
        if (spokenCursor > text.length) spokenCursor = 0

        val end = if (flushTail) text.length else findSpeakBoundary(text, spokenCursor)
        if (end <= spokenCursor) return

        val segment = text.substring(spokenCursor, end).trim()
        spokenCursor = end
        if (segment.isEmpty()) return

        Log.d(tag, "TTS鎾姤(segment): $segment")
        lastTtsRequestAt = SystemClock.elapsedRealtime()
        switchState(DigitalHumanState.SPEAKING)
        ensureTtsReady(showHint = false) { engine -> engine?.speak(segment) }
    }

    private fun findSpeakBoundary(text: String, start: Int): Int {
        for (i in start until text.length) {
            when (text[i]) {
                '\u3002', '\uFF01', '\uFF1F', '!', '?', '\n' -> {
                    val end = i + 1
                    if (end - start >= minSpeakChars) return end
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
        synchronized(asrReadyCallbacks) {
            asrReadyCallbacks.add(onReady)
            if (asrInitializing) return
            asrInitializing = true
        }
        if (showHint) Toast.makeText(this, R.string.asr_initializing, Toast.LENGTH_SHORT).show()
        thread(start = true, name = "asr-init") {
            val engine = GlobalAsrManager.acquire(applicationContext, this)
            asrEngine = engine
            asrInitializing = false
            val callbacks = synchronized(asrReadyCallbacks) {
                asrReadyCallbacks.toList().also { asrReadyCallbacks.clear() }
            }
            runOnUiThread { callbacks.forEach { it(engine) } }
        }
    }

    private fun ensureTtsReady(showHint: Boolean = true, onReady: (TtsEngine?) -> Unit) {
        val existing = ttsEngine
        if (existing != null) {
            onReady(existing)
            return
        }
        synchronized(ttsReadyCallbacks) {
            ttsReadyCallbacks.add(onReady)
            if (ttsInitializing) return
            ttsInitializing = true
        }
        if (showHint) Toast.makeText(this, R.string.tts_initializing, Toast.LENGTH_SHORT).show()
        thread(start = true, name = "tts-init") {
            val engine = try {
                SherpaTtsEngine(this)
            } catch (_: Throwable) {
                null
            }
            engine?.setOnIdleListener {
                mainHandler.postDelayed({
                    val elapsed = SystemClock.elapsedRealtime() - lastTtsRequestAt
                    if (!isListening && !aiRequesting && elapsed > 600L) {
                        switchState(DigitalHumanState.LEISURE)
                    }
                }, 120L)
            }
            ttsEngine = engine
            ttsInitializing = false
            val callbacks = synchronized(ttsReadyCallbacks) {
                ttsReadyCallbacks.toList().also { ttsReadyCallbacks.clear() }
            }
            runOnUiThread { callbacks.forEach { it(engine) } }
        }
    }

    private fun warmupEngines() {
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

    private fun ensureChatVisible() {
        if (binding.chatPanel.visibility != View.VISIBLE) {
            binding.chatPanel.visibility = View.VISIBLE
        }
    }

    private fun prepareLiveUserBubble() {
        ensureChatVisible()
        aiBubbleView = null
        val bubble = buildBubble("", false)
        binding.chatContainer.addView(bubble)
        userBubbleView = bubble
        scrollChatToBottom()
    }

    private fun updateLiveUserBubble(text: String) {
        val message = text.trim()
        if (message.isEmpty()) return
        val lastView = binding.chatContainer.getChildAt(binding.chatContainer.childCount - 1) as? TextView
        if (userBubbleView == null && lastView != null && lastView.text.toString() == message) {
            userBubbleView = lastView
            return
        }
        val bubble = userBubbleView ?: run {
            val created = buildBubble("", false)
            binding.chatContainer.addView(created)
            userBubbleView = created
            created
        }
        bubble.text = message
        scrollChatToBottom()
    }

    private fun updateAiBubble(text: String) {
        val message = text.trim()
        if (message.isEmpty()) return
        ensureChatVisible()
        val bubble = aiBubbleView ?: buildBubble("", true).also {
            aiBubbleView = it
            binding.chatContainer.addView(it)
        }
        bubble.text = message
        scrollChatToBottom()
    }

    private fun buildBubble(text: String, isAi: Boolean): TextView {
        val view = TextView(this)
        view.maxWidth = (resources.displayMetrics.widthPixels * 0.62f).toInt()
        view.text = text
        view.setTextColor(0xFFFFFFFF.toInt())
        view.textSize = 16f
        view.setPadding(18, 12, 18, 12)
        view.background = ContextCompat.getDrawable(this, if (isAi) R.drawable.bg_bubble_ai else R.drawable.bg_bubble_user)
        val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        params.gravity = if (isAi) Gravity.END else Gravity.START
        params.topMargin = 8
        view.layoutParams = params
        return view
    }

    private fun scrollChatToBottom() {
        binding.svChat.post { binding.svChat.fullScroll(View.FOCUS_DOWN) }
    }

    private fun syncFaceDetectionState() {
        if (!MMKVHelper.isFaceDetectEnabled()) {
            stopFaceDetection()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            binding.previewFace.visibility = View.GONE
            Log.w(faceTag, "camera permission missing, face detection disabled")
            return
        }
        binding.previewFace.visibility = View.VISIBLE
        faceController?.start(this, binding.previewFace)
    }

    private fun stopFaceDetection() {
        binding.previewFace.visibility = View.GONE
        faceController?.stop()
        hasUserDetected = false
        faceDetectedStartTime = 0L
        lastFaceDetected = false
    }

    private fun handleFaceDetectionResult(faceNow: Boolean) {
        if (faceNow) {
            if (!lastFaceDetected) {
                faceDetectedStartTime = System.currentTimeMillis()
                Log.d(faceTag, "face first seen")
            }
            val duration = System.currentTimeMillis() - faceDetectedStartTime
            if (duration >= faceStableMs && !hasUserDetected) {
                hasUserDetected = true
                Log.d(faceTag, "user detected (stable >=${faceStableMs}ms)")
                runOnUiThread {
                    Toast.makeText(this, "妫€娴嬪埌浜鸿劯", Toast.LENGTH_SHORT).show()
                    when {
                        MMKVHelper.isFaceAutoDialogEnabled() -> {
                            if (!isListening && !aiRequesting) {
                                binding.btnStartListen.performClick()
                            }
                        }
                        MMKVHelper.isFaceAutoGreetEnabled() -> {
                            if (!isListening && !aiRequesting) {
                                lastTtsRequestAt = SystemClock.elapsedRealtime()
                                switchState(DigitalHumanState.SPEAKING)
                                ensureTtsReady(showHint = false) { engine ->
                                    val ok = engine?.speak(getString(R.string.face_greet_text)) == true
                                    if (!ok) {
                                        switchState(DigitalHumanState.LEISURE)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            faceDetectedStartTime = 0L
            if (hasUserDetected) {
                hasUserDetected = false
                Log.d(faceTag, "user lost")
            }
        }
        lastFaceDetected = faceNow
    }
}

