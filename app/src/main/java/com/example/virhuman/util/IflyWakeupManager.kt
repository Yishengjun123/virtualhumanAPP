package com.example.virhuman.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.example.virhuman.BuildConfig
import com.iflytek.aikit.core.AiAudio
import com.iflytek.aikit.core.AiHandle
import com.iflytek.aikit.core.AiHelper
import com.iflytek.aikit.core.AiListener
import com.iflytek.aikit.core.AiRequest
import com.iflytek.aikit.core.AiResponse
import com.iflytek.aikit.core.AiStatus
import com.iflytek.aikit.core.AuthListener
import com.iflytek.aikit.core.BaseLibrary
import com.iflytek.aikit.core.ErrType
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

object IflyWakeupManager {
    private const val TAG = "IflyWakeup"
    private const val BUFFER_SIZE = 1280
    private const val SAMPLE_RATE = 16000

    private val isRecording = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var aiHandle: AiHandle? = null
    private var listenerRegistered = false
    private var inited = false

    private var workDir: String = ""
    private var callback: ((String) -> Unit)? = null

    fun setWakeupCallback(cb: (String) -> Unit) {
        callback = cb
    }

    fun ensureInit(context: Context): Boolean {
        if (inited) return true
        val appId = BuildConfig.XFYUN_APP_ID.trim()
        val apiKey = BuildConfig.XFYUN_API_KEY.trim()
        val apiSecret = BuildConfig.XFYUN_API_SECRET.trim()
        val ability = BuildConfig.XFYUN_ABILITY.trim()
        if (appId.isEmpty() || apiKey.isEmpty() || apiSecret.isEmpty() || ability.isEmpty()) {
            Log.e(TAG, "iflytek config missing, check BuildConfig XFYUN_* fields")
            return false
        }

        workDir = "${context.filesDir.absolutePath}/iflytek/aikit"
        try {
            // Use assets/ivw as the single source of truth. Do not rewrite keyword.txt.
            copyAssetFolder(context, "ivw", workDir)
        } catch (e: Exception) {
            Log.e(TAG, "copy ivw assets failed: ${e.message}", e)
            return false
        }

        val params = BaseLibrary.Params.builder()
            .appId(appId)
            .apiKey(apiKey)
            .apiSecret(apiSecret)
            .ability(ability)
            .workDir(workDir)
            .build()

        AiHelper.getInst().registerListener(authListener)
        AiHelper.getInst().initEntry(context.applicationContext, params)
        inited = true
        Log.d(TAG, "iflytek init entry requested, workDir=$workDir")
        return true
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startWakeup(context: Context): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "record audio permission missing")
            return false
        }
        if (!ensureInit(context)) return false

        val ability = BuildConfig.XFYUN_ABILITY.trim()
        if (!listenerRegistered) {
            AiHelper.getInst().registerListener(ability, wakeupListener)
            listenerRegistered = true
        }

        val ret = startWakeupEngine(ability)
        if (ret != 0) {
            Log.e(TAG, "start wakeup engine failed: $ret")
            return false
        }

        startRecording()
        Log.d(TAG, "wakeup started")
        return true
    }

    fun stopWakeup() {
        if (isRecording.get() && aiHandle != null) {
            val endBytes = ByteArray(BUFFER_SIZE)
            val endAudio = AiAudio.get("wav")
                .data(endBytes)
                .status(AiStatus.END)
                .valid()
            val request = AiRequest.builder().payload(endAudio).build()
            AiHelper.getInst().write(request, aiHandle)
        }

        stopRecording()

        aiHandle?.let {
            AiHelper.getInst().end(it)
            aiHandle = null
        }
        Log.d(TAG, "wakeup stopped")
    }

    fun release() {
        stopWakeup()
        if (inited) {
            AiHelper.getInst().unInit()
        }
        inited = false
        listenerRegistered = false
        callback = null
    }

    private val authListener = AuthListener { type, code ->
        when (type) {
            ErrType.AUTH -> Log.d(TAG, "auth result: $code")
            ErrType.HTTP -> Log.d(TAG, "http auth result: $code")
            else -> Log.d(TAG, "auth type=$type code=$code")
        }
    }

    private val wakeupListener = object : AiListener {
        override fun onResult(handleId: Int, outputData: List<AiResponse?>?, userData: Any?) {
            outputData?.forEach { item ->
                if (item?.key == "func_wake_up" || item?.key == "func_pre_wakeup") {
                    val text = item.value?.let { String(it) } ?: return@forEach
                    val keyword = parseKeyword(text)
                    if (keyword.isNotBlank()) {
                        Log.d(TAG, "keyword detected: $keyword")
                        callback?.invoke(keyword)
                    }
                }
            }
        }

        override fun onEvent(p0: Int, p1: Int, p2: List<AiResponse?>?, p3: Any?) {
            Log.d(TAG, "event: $p0,$p1")
        }

        override fun onError(p0: Int, p1: Int, p2: String?, p3: Any?) {
            Log.e(TAG, "error: $p0,$p1,$p2")
        }
    }

    private fun parseKeyword(resultJson: String): String {
        return try {
            val json = org.json.JSONObject(resultJson)
            val arr = json.optJSONArray("rlt") ?: return ""
            if (arr.length() <= 0) return ""
            arr.optJSONObject(0)?.optString("keyword", "").orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    private fun startWakeupEngine(ability: String): Int {
        val keywordFile = File(workDir, "keyword.txt")
        if (!keywordFile.exists()) {
            Log.e(TAG, "keyword file missing: ${keywordFile.absolutePath}")
            return -1
        }

        val loadReq = AiRequest.builder()
            .customText("key_word", keywordFile.absolutePath, 0)
            .build()
        var ret = AiHelper.getInst().loadData(ability, loadReq)
        if (ret != 0) return ret

        ret = AiHelper.getInst().specifyDataSet(ability, "key_word", intArrayOf(0))
        if (ret != 0) return ret

        val param = AiRequest.builder()
            .param("wdec_param_nCmThreshold", "0 0:800")
            .param("gramLoad", true)
            .build()

        aiHandle = AiHelper.getInst().start(ability, param, null)
        return aiHandle?.code ?: -1
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun createAudioRecord() {
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            BUFFER_SIZE
        )
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord = null
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startRecording() {
        if (isRecording.get()) return
        if (audioRecord == null) createAudioRecord()
        val recorder = audioRecord ?: return
        try {
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                return
            }
            isRecording.set(true)
            writeAudioDataLoop()
        } catch (e: Exception) {
            Log.e(TAG, "start recording failed: ${e.message}", e)
        }
    }

    private fun stopRecording() {
        if (!isRecording.get()) return
        isRecording.set(false)
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
    }

    private fun writeAudioDataLoop() {
        Thread {
            val buffer = ByteArray(BUFFER_SIZE)
            var first = true
            while (isRecording.get() && aiHandle != null) {
                val read = audioRecord?.read(buffer, 0, BUFFER_SIZE) ?: 0
                if (read <= 0) continue
                val status = if (first) AiStatus.BEGIN else AiStatus.CONTINUE
                first = false
                val audio = AiAudio.get("wav")
                    .data(buffer.copyOf(read))
                    .status(status)
                    .valid()
                val req = AiRequest.builder().payload(audio).build()
                AiHelper.getInst().write(req, aiHandle)
            }
        }.start()
    }

    private fun copyAssetFolder(context: Context, assetPath: String, destPath: String) {
        val children = context.assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            context.assets.open(assetPath).use { input ->
                val outFile = File(destPath)
                outFile.parentFile?.mkdirs()
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }

        val dir = File(destPath)
        if (!dir.exists()) dir.mkdirs()
        for (child in children) {
            val childAsset = "$assetPath/$child"
            val childDest = "$destPath/$child"
            copyAssetFolder(context, childAsset, childDest)
        }
    }
}
