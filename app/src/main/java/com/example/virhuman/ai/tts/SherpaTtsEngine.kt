package com.example.virhuman.ai.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SherpaTtsEngine(context: Context) : TtsEngine {
    private val tag = "SherpaTts"
    private var tts: OfflineTts? = null
    private var audioTrack: AudioTrack? = null
    private val synthExecutor = Executors.newSingleThreadExecutor()
    private val playQueue = LinkedBlockingQueue<AudioSegment>()
    private val running = AtomicBoolean(true)
    private val playing = AtomicBoolean(false)
    private val pendingSynthCount = AtomicInteger(0)
    private var playerThread: Thread? = null
    private var ready = false
    @Volatile
    private var speechRate = 1.0f

    @Volatile
    private var generation = 0

    @Volatile
    private var released = false

    @Volatile
    private var onIdleListener: (() -> Unit)? = null

    private data class AudioSegment(
        val pcm: ShortArray,
        val sampleRate: Int,
        val text: String,
        val generation: Int
    )

    init {
        try {
            val vitsConfig = OfflineTtsVitsModelConfig().apply {
                model = "sherpa/tts/model.onnx"
                lexicon = "sherpa/tts/lexicon.txt"
                tokens = "sherpa/tts/tokens.txt"
                dataDir = ""
                dictDir = "sherpa/tts/dict"
                noiseScale = 0.667f
                noiseScaleW = 0.8f
                // Slightly slower than default, more natural than playback-rate stretching.
                lengthScale = 1.08f
            }

            val modelConfig = OfflineTtsModelConfig().apply {
                vits = vitsConfig
                numThreads = 2
                debug = false
                provider = "cpu"
            }

            val config = OfflineTtsConfig().apply {
                model = modelConfig
                // Enable FST-based text normalization (date/number/heteronym/phone)
                ruleFsts = "sherpa/tts/phone.fst,sherpa/tts/date.fst,sherpa/tts/number.fst,sherpa/tts/new_heteronym.fst"
            }

            tts = OfflineTts(context.assets, config)
            ready = true
            startPlayerLoop()
        } catch (_: Exception) {
            ready = false
        }
    }

    override fun speak(text: String): Boolean {
        if (!ready || released || text.isBlank()) return false
        val localTts = tts ?: return false
        val currentGen = generation
        pendingSynthCount.incrementAndGet()
        synthExecutor.execute {
            if (released) {
                pendingSynthCount.decrementAndGet()
                return@execute
            }
            try {
                val generated = localTts.generate(text, 0, 1.0f)
                val samples = generated.samples
                if (samples.isEmpty()) return@execute
                Log.d(tag, "TTS播报(text): $text")
                Log.d(
                    tag,
                    "TTS播报(samples=${samples.size}, rate=${generated.sampleRate}, speechRate=$speechRate)"
                )

                val pcm = ShortArray(samples.size)
                for (i in samples.indices) {
                    val v = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
                    pcm[i] = v.toShort()
                }

                playQueue.put(AudioSegment(pcm, generated.sampleRate, text, currentGen))
            } catch (e: Exception) {
                Log.e(tag, "TTS播放失败: ${e.message}", e)
            } finally {
                pendingSynthCount.decrementAndGet()
                maybeNotifyIdle()
            }
        }
        return true
    }

    override fun isReady(): Boolean = ready

    override fun setSpeechRate(rate: Float) {
        speechRate = rate.coerceIn(0.6f, 1.3f)
    }

    override fun setOnIdleListener(listener: (() -> Unit)?) {
        onIdleListener = listener
    }

    override fun stop() {
        generation += 1
        playQueue.clear()
        pendingSynthCount.set(0)
        try {
            audioTrack?.stop()
        } catch (_: Exception) {
        }
        audioTrack?.release()
        audioTrack = null
        playing.set(false)
        onIdleListener?.invoke()
    }

    override fun release() {
        released = true
        stop()
        running.set(false)
        playerThread?.interrupt()
        playerThread = null
        tts?.release()
        tts = null
        ready = false
        synthExecutor.shutdownNow()
    }

    private fun startPlayerLoop() {
        playerThread = Thread({
            while (running.get()) {
                try {
                    val segment = playQueue.take()
                    if (segment.generation != generation) {
                        maybeNotifyIdle()
                        continue
                    }
                    playSegment(segment)
                    maybeNotifyIdle()
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(tag, "Player loop error: ${e.message}", e)
                }
            }
        }, "sherpa-tts-player").apply {
            isDaemon = true
            start()
        }
    }

    private fun playSegment(segment: AudioSegment) {
        playing.set(true)
        val minBuffer = AudioTrack.getMinBufferSize(
            segment.sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(segment.sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            maxOf(minBuffer, segment.pcm.size * 2),
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        audioTrack = track
        @Suppress("DEPRECATION")
        track.setPlaybackRate(segment.sampleRate)
        track.play()
        track.write(segment.pcm, 0, segment.pcm.size)
        waitForPlaybackComplete(track, segment.pcm.size)
        try {
            track.stop()
        } catch (_: Exception) {
        }
        track.release()
        if (audioTrack === track) {
            audioTrack = null
        }
        playing.set(false)
    }

    private fun waitForPlaybackComplete(track: AudioTrack, totalFrames: Int) {
        val startedAt = SystemClock.elapsedRealtime()
        val expectedMs = ((totalFrames * 1000.0) / track.sampleRate).toLong().coerceAtLeast(1L)
        val timeoutMs = expectedMs + 1200L
        while (running.get() && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
            val played = track.playbackHeadPosition
            if (played >= totalFrames) break
            if (SystemClock.elapsedRealtime() - startedAt > timeoutMs) break
            SystemClock.sleep(20)
        }
    }

    private fun maybeNotifyIdle() {
        if (released) return
        if (!running.get()) return
        if (playing.get()) return
        if (pendingSynthCount.get() > 0) return
        if (playQueue.isNotEmpty()) return
        onIdleListener?.invoke()
    }
}
