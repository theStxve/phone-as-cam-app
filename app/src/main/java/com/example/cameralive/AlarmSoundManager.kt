package com.example.cameralive

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Build
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.sin

object AlarmSoundManager {
    private const val TAG = "AlarmSoundManager"
    private const val SAMPLE_RATE = 44100

    private val isPlaying = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var stopFuture: ScheduledFuture<*>? = null
    private var audioTrack: AudioTrack? = null
    private var mediaPlayer: MediaPlayer? = null
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun isSirenPlaying(): Boolean = isPlaying.get()

    @Synchronized
    fun playSiren(durationSeconds: Int = 0) {
        stopFuture?.cancel(false)
        stopFuture = null

        if (isPlaying.get()) {
            if (durationSeconds > 0) {
                stopFuture = executor.schedule({ stopSiren() }, durationSeconds.toLong(), TimeUnit.SECONDS)
            }
            return
        }

        isPlaying.set(true)
        Log.i(TAG, "Starting deterrent siren sound (duration: ${if (durationSeconds > 0) "${durationSeconds}s" else "infinite loop"})...")

        executor.submit {
            val ctx = appContext
            var mpStarted = false

            if (ctx != null) {
                try {
                    // Try to play custom sound resource: R.raw.woooo
                    val resId = ctx.resources.getIdentifier("woooo", "raw", ctx.packageName)
                    if (resId != 0) {
                        val mp = MediaPlayer.create(ctx, resId)
                        if (mp != null) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                mp.setAudioAttributes(
                                    AudioAttributes.Builder()
                                        .setUsage(AudioAttributes.USAGE_ALARM)
                                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                        .build()
                                )
                            } else {
                                @Suppress("DEPRECATION")
                                mp.setAudioStreamType(AudioManager.STREAM_ALARM)
                            }
                            mp.isLooping = true
                            mp.setVolume(1.0f, 1.0f)
                            mp.start()
                            mediaPlayer = mp
                            mpStarted = true
                            Log.i(TAG, "Playing custom siren sound from res/raw/woooo.mp3 via MediaPlayer (looping)")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed playing custom sound woooo.mp3 via MediaPlayer, falling back to synthesizer", e)
                }
            }

            if (!mpStarted && isPlaying.get()) {
                // Fallback: synthesized siren
                playSynthesizedSiren()
            }
        }

        if (durationSeconds > 0) {
            stopFuture = executor.schedule({
                stopSiren()
            }, durationSeconds.toLong(), TimeUnit.SECONDS)
        }
    }

    private fun playSynthesizedSiren() {
        try {
            val bufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(SAMPLE_RATE * 2)

            val track = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_ALARM,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                    AudioTrack.MODE_STREAM
                )
            }

            audioTrack = track
            track.play()

            val chunkSamples = 1024
            val buffer = ShortArray(chunkSamples)
            var phase = 0.0
            var lfoPhase = 0.0
            val lfoSpeed = 2.5 * 2.0 * PI / SAMPLE_RATE

            while (isPlaying.get()) {
                for (i in 0 until chunkSamples) {
                    lfoPhase += lfoSpeed
                    if (lfoPhase > 2.0 * PI) lfoPhase -= 2.0 * PI

                    val currentFreq = 1000.0 + 350.0 * sin(lfoPhase)
                    val phaseInc = 2.0 * PI * currentFreq / SAMPLE_RATE
                    phase += phaseInc
                    if (phase > 2.0 * PI) phase -= 2.0 * PI

                    val sine = sin(phase)
                    val sampleVal = (sine.coerceIn(-0.85, 0.85) * 1.15 * Short.MAX_VALUE).toInt()
                    buffer[i] = sampleVal.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
                track.write(buffer, 0, chunkSamples)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating siren audio", e)
        } finally {
            cleanUpTrack()
        }
    }

    @Synchronized
    fun stopSiren() {
        if (!isPlaying.getAndSet(false)) return
        Log.i(TAG, "Stopping deterrent siren.")
        stopFuture?.cancel(false)
        cleanUpMediaPlayer()
        cleanUpTrack()
    }

    private fun cleanUpMediaPlayer() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up MediaPlayer", e)
        } finally {
            mediaPlayer = null
        }
    }

    private fun cleanUpTrack() {
        try {
            audioTrack?.let {
                it.pause()
                it.flush()
                it.stop()
                it.release()
            }
        } catch (e: Exception) {
            // ignore cleanup error
        } finally {
            audioTrack = null
        }
    }
}
