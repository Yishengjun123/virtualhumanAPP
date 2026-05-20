package com.example.virhuman.ai.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class SherpaTtsEngine(context: Context) : TtsEngine {
    private val tag = "SherpaTts"
    private var tts: OfflineTts? = null
    private var audioTrack: AudioTrack? = null
    private var audioTrackSampleRate = 0
    private val audioLock = Any()
    private val synthExecutor = Executors.newFixedThreadPool(2)
    private val pendingSegments = ConcurrentHashMap<Long, AudioSegment>()
    private val pendingLock = Object()
    private val enqueueSeq = AtomicLong(0L)
    private val running = AtomicBoolean(true)
    private val playing = AtomicBoolean(false)
    private val pendingSynthCount = AtomicInteger(0)
    private val bufferedCount = AtomicInteger(0)
    private var playerThread: Thread? = null
    private var ready = false

    @Volatile
    private var speechRate = 1.0f

    @Volatile
    private var speakerId = 0

    @Volatile
    private var generation = 0

    @Volatile
    private var nextPlaySeq = 0L

    @Volatile
    private var released = false

    @Volatile
    private var onIdleListener: (() -> Unit)? = null

    @Volatile
    private var onSegmentDoneListener: (() -> Unit)? = null

    private data class AudioSegment(
        val sequence: Long,
        val pcm: ShortArray,
        val sampleRate: Int,
        val text: String,
        val generation: Int
    )

    init {
        try {
            val vitsConfig = OfflineTtsVitsModelConfig().apply {
                model = "sherpa/tts/vits-zh-hf-fanchen-C.onnx"
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
        val sequence = enqueueSeq.getAndIncrement()
        pendingSynthCount.incrementAndGet()
        synthExecutor.execute {
            if (released) {
                pendingSynthCount.decrementAndGet()
                return@execute
            }
            try {
                val generated = localTts.generate(text, speakerId, speechRate)
                val samples = generated.samples
                if (samples.isEmpty()) return@execute
                Log.d(tag, "TTS闁圭虎鍘芥慨?text): $text")
                Log.d(
                    tag,
                    "TTS闁圭虎鍘芥慨?samples=${samples.size}, rate=${generated.sampleRate}, speechRate=$speechRate)"
                )

                val rawPcm = ShortArray(samples.size)
                for (i in samples.indices) {
                    val v = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
                    rawPcm[i] = v.toShort()
                }

                val pcm = postProcessPcm(rawPcm)
                if (pcm.isEmpty()) return@execute

                if (currentGen == generation && !released) {
                    pendingSegments[sequence] = AudioSegment(sequence, pcm, generated.sampleRate, text, currentGen)
                    bufferedCount.incrementAndGet()
                    synchronized(pendingLock) {
                        pendingLock.notifyAll()
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "TTS闁圭虎鍘介弬浣瑰緞鏉堫偉袝: ${e.message}", e)
            } finally {
                pendingSynthCount.decrementAndGet()
                synchronized(pendingLock) {
                    pendingLock.notifyAll()
                }
                maybeNotifyIdle()
            }
        }
        return true
    }

    override fun isReady(): Boolean = ready

    override fun setSpeechRate(rate: Float) {
        speechRate = rate.coerceIn(0.7f, 1.6f)
    }

    override fun setOnIdleListener(listener: (() -> Unit)?) {
        onIdleListener = listener
    }

    override fun setOnSegmentDoneListener(listener: (() -> Unit)?) {
        onSegmentDoneListener = listener
    }


    override fun setSpeakerId(speakerId: Int) {
        this.speakerId = speakerId.coerceAtLeast(0)
    }

    override fun stop() {
        generation += 1
        enqueueSeq.set(0L)
        nextPlaySeq = 0L
        pendingSegments.clear()
        bufferedCount.set(0)
        pendingSynthCount.set(0)
        synchronized(pendingLock) {
            pendingLock.notifyAll()
        }

        synchronized(audioLock) {
            audioTrack?.let { track ->
                try {
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        track.pause()
                    }
                    track.flush()
                } catch (_: Exception) {
                }
            }
        }

        playing.set(false)
        onIdleListener?.invoke()
    }

    override fun release() {
        released = true
        stop()
        running.set(false)
        synchronized(pendingLock) {
            pendingLock.notifyAll()
        }
        playerThread?.interrupt()
        playerThread = null

        synchronized(audioLock) {
            audioTrack?.release()
            audioTrack = null
            audioTrackSampleRate = 0
        }

        tts?.release()
        tts = null
        ready = false
        synthExecutor.shutdownNow()
    }
    //TTS 内部做了并行预合成 + 顺序播放  bufferedCount
    private fun startPlayerLoop() {
        playerThread = Thread({
            while (running.get()) {
                try {
                    var segment: AudioSegment? = null
                    synchronized(pendingLock) {
                        while (running.get() && !released) {
                            segment = pendingSegments.remove(nextPlaySeq)
                            if (segment != null) {
                                bufferedCount.decrementAndGet()
                                nextPlaySeq += 1
                                break
                            }
                            val hasMoreWork = pendingSynthCount.get() > 0 || bufferedCount.get() > 0
                            if (!hasMoreWork) {
                                maybeNotifyIdle()
                            }
                            pendingLock.wait(60L)
                        }
                    }

                    val readySegment = segment ?: continue
                    if (readySegment.generation != generation) {
                        maybeNotifyIdle()
                        continue
                    }
                    playSegment(readySegment)
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
        val track = ensureAudioTrack(segment.sampleRate) ?: return
        val startHead = track.playbackHeadPosition
        playing.set(true)
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
            track.play()
        }
        writeAll(track, segment.pcm)
        waitForPlaybackComplete(track, startHead, segment.pcm.size)
        playing.set(false)
        onSegmentDoneListener?.invoke()
    }

    private fun ensureAudioTrack(sampleRate: Int): AudioTrack? {
        synchronized(audioLock) {
            if (audioTrack != null && audioTrackSampleRate == sampleRate) {
                return audioTrack
            }
            audioTrack?.release()
            audioTrack = null

            val minBuffer = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            val track = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
                maxOf(minBuffer * 4, sampleRate / 2),
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                track.setVolume(1.0f)
            } else {
                @Suppress("DEPRECATION")
                track.setStereoVolume(1.0f, 1.0f)
            }
            @Suppress("DEPRECATION")
            track.setPlaybackRate(sampleRate)
            audioTrack = track
            audioTrackSampleRate = sampleRate
            return track
        }
    }

    private fun writeAll(track: AudioTrack, pcm: ShortArray) {
        var offset = 0
        while (offset < pcm.size && running.get() && !released) {
            val written = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                track.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
            } else {
                track.write(pcm, offset, pcm.size - offset)
            }
            if (written <= 0) break
            offset += written
        }
    }

    private fun waitForPlaybackComplete(track: AudioTrack, startHead: Int, totalFrames: Int) {
        val startedAt = SystemClock.elapsedRealtime()
        val expectedMs = ((totalFrames * 1000.0) / track.sampleRate).toLong().coerceAtLeast(1L)
        val timeoutMs = expectedMs + 1200L
        while (running.get() && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
            val played = track.playbackHeadPosition - startHead
            if (played >= totalFrames) break
            if (SystemClock.elapsedRealtime() - startedAt > timeoutMs) break
            SystemClock.sleep(20)
        }
    }

    private fun postProcessPcm(input: ShortArray): ShortArray {
        if (input.isEmpty()) return input

        val threshold = 220
        var start = 0
        while (start < input.size && kotlin.math.abs(input[start].toInt()) < threshold) {
            start++
        }

        var end = input.size - 1
        while (end > start && kotlin.math.abs(input[end].toInt()) < threshold) {
            end--
        }

        val trimmed = if (start == 0 && end == input.size - 1) {
            input
        } else {
            input.copyOfRange(start.coerceAtMost(input.size - 1), (end + 1).coerceAtMost(input.size))
        }
        if (trimmed.isEmpty()) return input

        var peak = 0
        for (s in trimmed) {
            val a = kotlin.math.abs(s.toInt())
            if (a > peak) peak = a
        }
        if (peak <= 0) return trimmed

        val targetPeak = 28000f
        val gain = (targetPeak / peak.toFloat()).coerceIn(1.0f, 1.6f)
        if (gain <= 1.001f) return trimmed

        val out = ShortArray(trimmed.size)
        for (i in trimmed.indices) {
            val v = (trimmed[i] * gain).toInt().coerceIn(-32768, 32767)
            out[i] = v.toShort()
        }
        return out
    }
    private fun maybeNotifyIdle() {
        if (released) return
        if (!running.get()) return
        if (playing.get()) return
        if (pendingSynthCount.get() > 0) return
        if (bufferedCount.get() > 0) return
        onIdleListener?.invoke()
    }
}

