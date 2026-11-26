package com.example.ai

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Lightweight, leak-safe Android MediaRecorder controller for capturing live microphone
 * voice notes (.m4a / AAC) that are transcribed via `gemini-3.5-transcribe`.
 */
class MicAudioRecorderController(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var isRecordingFlag: Boolean = false

    val isRecording: Boolean
        get() = isRecordingFlag

    fun startRecording(): Result<File> {
        stopAndReleaseSilently()
        return runCatching {
            val file = File(
                context.cacheDir,
                "tlc_voice_note_${System.currentTimeMillis()}.m4a"
            )
            val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mediaRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16_000)
                setAudioEncodingBitRate(64_000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = mediaRecorder
            outputFile = file
            isRecordingFlag = true
            file
        }.onFailure { err ->
            Log.e("MicAudioRecorder", "Failed to start microphone recording", err)
            cancelAndDelete()
        }
    }

    fun stopRecording(): File? {
        if (!isRecordingFlag) return null
        val captured = outputFile
        isRecordingFlag = false
        runCatching {
            recorder?.stop()
        }.onFailure { err ->
            Log.w("MicAudioRecorder", "MediaRecorder.stop() warning: ${err.message}")
        }
        runCatching { recorder?.release() }
        recorder = null
        return captured?.takeIf { it.exists() && it.length() > 0L }
    }

    fun cancelAndDelete() {
        val file = outputFile
        stopAndReleaseSilently()
        runCatching { file?.delete() }
        outputFile = null
    }

    private fun stopAndReleaseSilently() {
        isRecordingFlag = false
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
    }
}
