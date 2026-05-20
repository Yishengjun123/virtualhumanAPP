package com.example.virhuman.ui.main

import android.Manifest
import android.content.Intent
import android.graphics.BitmapFactory
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
import com.example.virhuman.data.ResourceParser
import com.example.virhuman.databinding.ActivityMainBinding
import com.example.virhuman.ui.settings.SettingsActivity
import com.example.virhuman.util.IflyWakeupManager
import com.example.virhuman.util.WifiMonitor
import com.example.virhuman.video.DigitalHumanState
import com.example.virhuman.video.DigitalHumanVideoPlayer
import com.example.virhuman.vision.FaceDetectionController
import java.io.File
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), AsrEngine.Callback {
    private val tag = "MainVoiceChain"
    private val faceTag = "FaceDetect"
    private val minSpeakChars = 5
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
    @Volatile
    private var continuousAutoListenPending = false

    @Volatile
    private var aiStreamDoneForCurrentTurn = false

    private val ttsEnqueuedCount = AtomicInteger(0)
    private val ttsPlayedCount = AtomicInteger(0)


    private var aiBubbleView: TextView? = null
    private var userBubbleView: TextView? = null

    private var faceController: FaceDetectionController? = null
    private var hasUserDetected = false
    private var faceDetectedStartTime = 0L
    private var lastFaceDetected = false
    private val faceStableMs = 1000L
    private val asrReadyCallbacks = mutableListOf<(AsrEngine?) -> Unit>()
    private val ttsReadyCallbacks = mutableListOf<(TtsEngine?) -> Unit>()

    private data class CharacterProfile(
        val id: String,
        val displayNameRes: Int = 0,
        val name: String = "",
        val introduce: String = "",
        val picturePathOrUrl: String = "",
        val aiAppId: String,
        val stateVideoMap: Map<DigitalHumanState, String>,
        val speakerId: Int,
        val speechRate: Float
    )
    //样例数据
    private var characters: List<CharacterProfile> = listOf(
        CharacterProfile(
            id = "character_1",
            displayNameRes = R.string.character_1_name,
            aiAppId = BuildConfig.AI_APP_ID,
            stateVideoMap = mapOf(
                DigitalHumanState.LEISURE to "asset:///videos/leisure.mp4",
                DigitalHumanState.LISTENING to "asset:///videos/listening.mp4",
                DigitalHumanState.SPEAKING to "asset:///videos/speaking.mp4"
            ),
            speakerId = 0,
            speechRate = 1.3f
        ),
        CharacterProfile(
            id = "character_2",
            displayNameRes = R.string.character_2_name,
            aiAppId = "f3bcd634bc994312bd218e9e5f386aa2",
            stateVideoMap = mapOf(
                DigitalHumanState.LEISURE to "asset:///videos/character2/leisure.mp4",
                DigitalHumanState.LISTENING to "asset:///videos/character2/listening.mp4",
                DigitalHumanState.SPEAKING to "asset:///videos/character2/speaking.mp4"
            ),
            speakerId = 88,
            speechRate = 1.4f
        )
    )

    private var currentCharacterIndex = 0
    private var currentSpeakerId = 0
    private var currentSpeechRate = 1.3f
    private var wakeupStarted = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        videoPlayer = DigitalHumanVideoPlayer(this)
        videoPlayer.bind(binding.playerViewFront, binding.playerViewBack)
        characters = loadCharactersFromStorage()
        if (characters.isEmpty()) {
            Toast.makeText(this, R.string.resource_empty, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val savedId = MMKVHelper.getCurrentCharacterId()
        val idx = characters.indexOfFirst { it.id == savedId }.let { if (it >= 0) it else 0 }
        applyCharacter(characters[idx], idx, fromStartup = true)
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
        IflyWakeupManager.setWakeupCallback { keyword ->
            runOnUiThread { handleWakeupKeyword(keyword) }
        }
        //点击按钮开始识别，识别后进入onfinalresult
        binding.btnStartListen.setOnClickListener {
            if (isListening) {
                awaitingAsrFinal = false
                asrEngine?.stopListening()
                isListening = false
                isFinalizingToAi = false
                resetContinuousRoundState()
                binding.btnStartListen.setText(R.string.start_asr_short)
                if (!aiRequesting) {
                    switchState(DigitalHumanState.LEISURE)
                }
                return@setOnClickListener
            }
            ensureAudioPermission {
                stopWakeupIfRunning()
                ensureAsrReady { engine ->
                    if (engine == null || !engine.isAvailable()) {
                        Toast.makeText(this, R.string.asr_not_available, Toast.LENGTH_SHORT).show()
                        startWakeupIfNeeded()
                        return@ensureAsrReady
                    }
                    prepareLiveUserBubble()
                    switchState(DigitalHumanState.LISTENING)
                    isFinalizingToAi = false
                    awaitingAsrFinal = true
                    resetContinuousRoundState()
                    engine.startListening()
                    isListening = true
                    binding.btnStartListen.setText(R.string.stop_asr_short)
                }
            }
        }

        binding.btnTtsTest.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnSwitchCharacter.setOnClickListener {
            showCharacterDialogWithInfo()
        }

        binding.btnDeviceInfo.setOnClickListener {
            val model = Build.MODEL
            val deviceCode = Build.DEVICE
            Log.d(tag, "DeviceInfo model=$model, deviceCode=$deviceCode")
            Toast.makeText(this, getString(R.string.device_info_toast, model, deviceCode), Toast.LENGTH_SHORT).show()
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
    //在得到asr结果后更新字幕切换状态，发送给智能体sendtoai
    override fun onFinalResult(text: String) {
        awaitingAsrFinal = false
        isFinalizingToAi = true
        runOnUiThread {
            updateLiveUserBubble(text)
            userBubbleView = null
            isListening = false
            binding.btnStartListen.setText(R.string.start_asr_short)
            startWakeupIfNeeded()
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
            startWakeupIfNeeded()
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
            startWakeupIfNeeded()
            if (!aiRequesting && !isFinalizingToAi && !awaitingAsrFinal) {
                switchState(DigitalHumanState.LEISURE)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resetContinuousRoundState()
        WifiMonitor.startMonitoring()
        syncFaceDetectionState()
        startWakeupIfNeeded()
    }

    override fun onPause() {
        super.onPause()
        resetContinuousRoundState()
        WifiMonitor.stopMonitoring()
        stopFaceDetection()
        stopWakeupIfRunning()
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
        IflyWakeupManager.release()
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
        resetContinuousRoundState()

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
                mergedText = mergeStreamText(mergedText, chunk)
                runOnUiThread { updateAiBubble(mergedText) }
                speakReadySentences(mergedText, flushTail = false)
            },
            onDone = {
                aiRequesting = false
                val finalReply = mergedText.trim()
                if (finalReply.isBlank()) {
                    resetContinuousRoundState()
                    isFinalizingToAi = false
                    runOnUiThread {
                        Toast.makeText(this, R.string.ai_empty_reply, Toast.LENGTH_SHORT).show()
                    }
                    return@streamCall
                }
                runOnUiThread { updateAiBubble(finalReply) }
                //得到ai完整回复后给到tts，进行播报
                speakReadySentences(finalReply, flushTail = true)
                aiStreamDoneForCurrentTurn = true
                continuousAutoListenPending = MMKVHelper.isContinuousDialogEnabled() && ttsEnqueuedCount.get() > 0
                isFinalizingToAi = false
            },
            onError = { message ->
                aiRequesting = false
                isFinalizingToAi = false
                resetContinuousRoundState()
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

        lastTtsRequestAt = SystemClock.elapsedRealtime()
        switchState(DigitalHumanState.SPEAKING)
        ensureTtsReady(showHint = false) { engine ->
            val ok = engine?.speak(segment) == true
            if (ok) {
                ttsEnqueuedCount.incrementAndGet()
            }
        }
    }
    //由于ai是流式返回，所以tts为了体感上说出的更快，所以根据标点进行切割句子，把每个小句子先送给tts
    private fun findSpeakBoundary(text: String, start: Int): Int {
        for (i in start until text.length) {
            when (text[i]) {
                '\u3002', '\uFF01', '\uFF1F', '!', '?', '\uFF0C', ',', '\uFF1B', ';', '\n' -> {
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
            existing.setSpeakerId(currentSpeakerId)
            existing.setSpeechRate(currentSpeechRate)
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
            engine?.setSpeakerId(currentSpeakerId)
            engine?.setSpeechRate(currentSpeechRate)
            engine?.setOnSegmentDoneListener {
                ttsPlayedCount.incrementAndGet()
            }
            engine?.setOnIdleListener {
                mainHandler.postDelayed({
                    val elapsed = SystemClock.elapsedRealtime() - lastTtsRequestAt
                    if (isListening || aiRequesting || isFinalizingToAi || elapsed <= 600L) {
                        return@postDelayed
                    }
                    val ttsRoundComplete = aiStreamDoneForCurrentTurn &&
                        ttsEnqueuedCount.get() > 0 &&
                        ttsPlayedCount.get() >= ttsEnqueuedCount.get()

                    if (MMKVHelper.isContinuousDialogEnabled() && continuousAutoListenPending && ttsRoundComplete) {
                        resetContinuousRoundState()
                        if (binding.btnStartListen.text.toString() == getString(R.string.start_asr_short)) {
                            binding.btnStartListen.performClick()
                        }
                    } else {
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

    private fun resetContinuousRoundState() {
        continuousAutoListenPending = false
        aiStreamDoneForCurrentTurn = false
        ttsEnqueuedCount.set(0)
        ttsPlayedCount.set(0)
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


    private fun startWakeupIfNeeded() {
        if (wakeupStarted) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val ok = IflyWakeupManager.startWakeup(this)
        wakeupStarted = ok
        if (!ok) {
            Toast.makeText(this, R.string.wakeup_start_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopWakeupIfRunning() {
        if (!wakeupStarted) return
        IflyWakeupManager.stopWakeup()
        wakeupStarted = false
    }

    private fun handleWakeupKeyword(raw: String) {
        val keyword = raw.trim()
        if (keyword.isEmpty()) return
        val normalized = keyword
            .replace(" ", "")
            .replace(";", "")
            .replace("；", "")
            .replace("。", "")
            .replace("，", "")
        Log.d(tag, "Wakeup raw=$keyword normalized=$normalized")
        when {
            normalized.contains("\u4f60\u597d\u901a\u901a") || normalized.contains("\u4f60\u597d\u8c5a\u5b9d") || normalized == "\u4f60\u597d" -> {
                if (!isListening && !aiRequesting) {
                    binding.btnStartListen.performClick()
                }
            }
            normalized.contains("\u6682\u505c\u4e00\u4e0b") -> {
                ttsEngine?.stop()
                DashScopeManager.cancelCurrentStreaming()
                aiRequesting = false
                isFinalizingToAi = false
                resetContinuousRoundState()
                switchState(DigitalHumanState.LEISURE)
            }
            normalized.contains("\u518d\u89c1") -> {
                ttsEngine?.stop()
                DashScopeManager.cancelCurrentStreaming()
                aiRequesting = false
                isFinalizingToAi = false
                resetContinuousRoundState()
                switchState(DigitalHumanState.LEISURE)
                clearChatForGoodbye()
            }
        }
    }

    private fun clearChatForGoodbye() {
        AiSession.updateSessionId()
        aiBubbleView = null
        userBubbleView = null
        spokenCursor = 0
        awaitingAsrFinal = false
        isFinalizingToAi = false
        aiRequesting = false
        resetContinuousRoundState()
        binding.chatContainer.removeAllViews()
        binding.chatPanel.visibility = View.GONE
    }
    private fun showCharacterDialog() {
        if (isListening || aiRequesting || isFinalizingToAi) {
            Toast.makeText(this, R.string.switch_character_busy, Toast.LENGTH_SHORT).show()
            return
        }
        val names = characters.map { getString(it.displayNameRes) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.switch_character_title)
            .setItems(names) { _, which ->
                if (which == currentCharacterIndex) return@setItems
                applyCharacter(characters[which], which, fromStartup = false)
            }
            .show()
    }


    private fun showCharacterDialogWithInfo() {
        if (isListening || aiRequesting || isFinalizingToAi) {
            Toast.makeText(this, R.string.switch_character_busy, Toast.LENGTH_SHORT).show()
            return
        }
        if (characters.isEmpty()) {
            Toast.makeText(this, R.string.resource_empty, Toast.LENGTH_SHORT).show()
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 18, 28, 8)
        }
        var dialogRef: AlertDialog? = null

        characters.forEachIndexed { index, profile ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 12, 0, 12)
            }
            val image = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(140, 140)
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageResource(android.R.drawable.ic_menu_gallery)
            }
            val textBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 18
                }
            }
            val title = TextView(this).apply {
                text = if (profile.name.isNotBlank()) profile.name else getString(profile.displayNameRes)
                textSize = 17f
                setTextColor(0xFF111111.toInt())
            }
            val intro = TextView(this).apply {
                text = profile.introduce
                textSize = 13f
                setTextColor(0xFF666666.toInt())
            }
            textBox.addView(title)
            textBox.addView(intro)
            row.addView(image)
            row.addView(textBox)
            container.addView(row)

            bindCharacterImage(image, profile.picturePathOrUrl)
            image.setOnClickListener {
                if (index != currentCharacterIndex) {
                    applyCharacter(profile, index, fromStartup = false)
                }
                dialogRef?.dismiss()
            }
        }

        dialogRef = AlertDialog.Builder(this)
            .setTitle(R.string.switch_character_title)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialogRef?.show()
    }
    private fun applyCharacter(profile: CharacterProfile, index: Int, fromStartup: Boolean) {
        val missing = findMissingVideo(profile)
        if (missing != null) {
            Toast.makeText(
                this,
                getString(R.string.switch_character_missing_video, missing),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val appId = profile.aiAppId.trim().ifBlank { BuildConfig.AI_APP_ID }
        DashScopeManager.updateCredentials(appId, BuildConfig.AI_API_KEY)

        currentCharacterIndex = index
        MMKVHelper.saveCurrentCharacterId(profile.id)
        currentSpeakerId = profile.speakerId
        currentSpeechRate = profile.speechRate
        currentState = null
        clearChatForCharacterSwitch()
        ttsEngine?.setSpeakerId(currentSpeakerId)
        ttsEngine?.setSpeechRate(currentSpeechRate)
        videoPlayer.updateAssetMap(profile.stateVideoMap, DigitalHumanState.LEISURE)
        switchState(DigitalHumanState.LEISURE)

        if (!fromStartup) {
            Toast.makeText(
                this,
                getString(R.string.switch_character_success, profile.name),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun clearChatForCharacterSwitch() {
        aiBubbleView = null
        userBubbleView = null
        spokenCursor = 0
        awaitingAsrFinal = false
        isFinalizingToAi = false
        aiRequesting = false
        binding.chatContainer.removeAllViews()
        binding.chatPanel.visibility = View.GONE
        ttsEngine?.stop()
        DashScopeManager.cancelCurrentStreaming()
    }

    private fun findMissingVideo(profile: CharacterProfile): String? {
        for ((state, uri) in profile.stateVideoMap) {
            val exists = uriPathExists(uri)
            Log.d(tag, "VideoCheck character=${profile.id} state=$state uri=$uri exists=$exists")
            if (!exists) {
                return "$state -> $uri"
            }
        }
        return null
    }

    private fun uriPathExists(uri: String): Boolean {
        return when {
            uri.startsWith("asset:///") -> {
                val relPath = uri.removePrefix("asset:///")
                try {
                    assets.open(relPath).use { }
                    true
                } catch (_: Exception) {
                    false
                }
            }
            uri.startsWith("file://") -> File(uri.removePrefix("file://")).exists()
            uri.startsWith("http://") || uri.startsWith("https://") -> true
            else -> File(uri).exists()
        }
    }

    private fun loadCharactersFromStorage(): List<CharacterProfile> {
        val jsonText = MMKVHelper.getResourceJson()
        if (jsonText.isBlank()) return emptyList()
        val parsed = try {
            ResourceParser.parseResponse(jsonText)
        } catch (e: Exception) {
            Log.e(tag, "parse resource failed: ${e.message}", e)
            return emptyList()
        }
        if (parsed.code != 0 || parsed.characters.isEmpty()) return emptyList()

        return parsed.characters.map { character ->
            val leisureLocal = MMKVHelper.getVideoLocalPath(character.id, "leisure")
            val listeningLocal = MMKVHelper.getVideoLocalPath(character.id, "listening")
            val speakingLocal = MMKVHelper.getVideoLocalPath(character.id, "speaking")
            val pictureLocal = MMKVHelper.getPictureLocalPath(character.id)

            val leisureUri = if (leisureLocal.isNotBlank() && File(leisureLocal).exists()) "file://${leisureLocal}" else character.leisureUrl
            val listeningUri = if (listeningLocal.isNotBlank() && File(listeningLocal).exists()) "file://${listeningLocal}" else character.listeningUrl
            val speakingUri = if (speakingLocal.isNotBlank() && File(speakingLocal).exists()) "file://${speakingLocal}" else character.speakingUrl
            val pictureUri = if (pictureLocal.isNotBlank() && File(pictureLocal).exists()) "file://${pictureLocal}" else character.picture

            Log.d(tag, "ResourceMap id=${character.id}, leisure=$leisureUri, listening=$listeningUri, speaking=$speakingUri")
            CharacterProfile(
                id = character.id,
                name = character.name,
                introduce = character.introduce,
                picturePathOrUrl = pictureUri,
                aiAppId = character.aiAppId,
                stateVideoMap = mapOf(
                    DigitalHumanState.LEISURE to leisureUri,
                    DigitalHumanState.LISTENING to listeningUri,
                    DigitalHumanState.SPEAKING to speakingUri
                ),
                speakerId = character.speakerId,
                speechRate = if (character.id == "2") 1.4f else 1.3f
            )
        }
    }

    private fun bindCharacterImage(imageView: ImageView, pathOrUrl: String) {
        if (pathOrUrl.isBlank()) return
        thread(start = true, name = "character-image") {
            try {
                val bitmap = when {
                    pathOrUrl.startsWith("file://") -> BitmapFactory.decodeFile(pathOrUrl.removePrefix("file://"))
                    pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://") -> URL(pathOrUrl).openStream().use { BitmapFactory.decodeStream(it) }
                    else -> null
                }
                if (bitmap != null) {
                    runOnUiThread { imageView.setImageBitmap(bitmap) }
                }
            } catch (_: Exception) {
            }
        }
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
                    Toast.makeText(this, R.string.face_detected_toast, Toast.LENGTH_SHORT).show()
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





























































