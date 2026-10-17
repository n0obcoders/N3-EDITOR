package com.nitinshukla.notebook.ui.voice

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/** Thin wrapper over MediaRecorder producing AAC in an MP4 container. */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var startedAt = 0L
    private var pausedTotal = 0L
    private var pausedAt = 0L
    var file: File? = null
        private set
    val isRecording: Boolean get() = recorder != null
    var isPaused: Boolean = false
        private set

    fun start(target: File) {
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioEncodingBitRate(96_000)
        r.setAudioSamplingRate(44_100)
        r.setOutputFile(target.absolutePath)
        r.prepare()
        r.start()
        recorder = r
        file = target
        startedAt = System.currentTimeMillis()
        pausedTotal = 0
        isPaused = false
    }

    fun pause() {
        recorder?.pause()
        pausedAt = System.currentTimeMillis()
        isPaused = true
    }

    fun resume() {
        recorder?.resume()
        pausedTotal += System.currentTimeMillis() - pausedAt
        isPaused = false
    }

    fun elapsedMs(): Long {
        if (recorder == null) return 0
        val end = if (isPaused) pausedAt else System.currentTimeMillis()
        return end - startedAt - pausedTotal
    }

    /** Stops and returns the duration, or null if nothing usable was recorded. */
    fun stop(): Long? {
        val r = recorder ?: return null
        val duration = elapsedMs()
        return try {
            r.stop()
            duration
        } catch (e: RuntimeException) {
            file?.delete()
            null
        } finally {
            r.release()
            recorder = null
        }
    }

    fun cancel() {
        try { recorder?.stop() } catch (e: RuntimeException) { /* nothing recorded */ }
        recorder?.release()
        recorder = null
        file?.delete()
        file = null
    }
}

/** Plays one attachment at a time. */
class AudioPlayer {
    private var player: MediaPlayer? = null
    var playingFile: String? = null
        private set

    fun toggle(file: File, onComplete: () -> Unit) {
        if (playingFile == file.name) {
            stop()
            return
        }
        stop()
        player = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener { stop(); onComplete() }
            prepare()
            start()
        }
        playingFile = file.name
    }

    fun stop() {
        player?.release()
        player = null
        playingFile = null
    }
}
