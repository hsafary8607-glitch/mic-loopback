package com.yourappname

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * مرحله ۷ — تغییرات نسبت به نسخه‌ی قبل (مرحله ۶):
 *   فقط یک چیز اضافه شد: محاسبه‌ی «تاخیر تقریبی» (approxLatencyMs) که در
 *   رابط کاربری جدید (MainActivity) نمایش داده می‌شه. هیچ منطق صوتی دیگه‌ای
 *   تغییر نکرده.
 *
 * این فایل جایگزین AudioLoopbackManager.kt نسخه‌ی قبلی می‌شه (همون مسیر، همون اسم).
 */
class AudioLoopbackManager(private val context: Context) {

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var loopThread: Thread? = null

    @Volatile
    private var isRunning = false

    // تاخیر تقریبی به میلی‌ثانیه (فقط برای نمایش در UI، یک عدد تخمینی است نه دقیق)
    @Volatile
    var approxLatencyMs: Int = 0
        private set

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    companion object {
        private const val TAG = "AudioLoopbackManager"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    interface LoopbackListener {
        fun onLoopbackError(message: String)
        fun onLoopbackStarted()
        fun onLoopbackStopped()
    }

    private var listener: LoopbackListener? = null

    fun setLoopbackListener(listener: LoopbackListener) {
        this.listener = listener
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun forceOutputToSpeaker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val speakerDevice = audioManager.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }
            if (speakerDevice != null) {
                val success = audioManager.setCommunicationDevice(speakerDevice)
                Log.d(TAG, "setCommunicationDevice(اسپیکر داخلی) موفق: $success")
            } else {
                Log.w(TAG, "دستگاه اسپیکر داخلی پیدا نشد، استفاده از روش قدیمی")
                audioManager.isSpeakerphoneOn = true
            }
        } else {
            audioManager.isSpeakerphoneOn = true
        }
    }

    private fun setupEchoCancellation(audioSessionId: Int) {
        if (!AcousticEchoCanceler.isAvailable()) {
            Log.d(TAG, "AcousticEchoCanceler روی این گوشی موجود نیست")
            return
        }
        try {
            echoCanceler = AcousticEchoCanceler.create(audioSessionId)
            echoCanceler?.enabled = true
            Log.d(TAG, "AcousticEchoCanceler فعال شد: ${echoCanceler?.enabled}")
        } catch (e: Exception) {
            Log.w(TAG, "فعال‌سازی AcousticEchoCanceler ناموفق بود: ${e.message}")
            echoCanceler = null
        }
    }

    private fun buildLowLatencyAudioTrack(minTrackBuf: Int): AudioTrack {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNEL_OUT)
            .setEncoding(AUDIO_FORMAT)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                return AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(minTrackBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()
            } catch (e: Exception) {
                Log.w(TAG, "ساخت AudioTrack با حالت کم‌تاخیر ناموفق بود، رفتن به حالت عادی: ${e.message}")
            }
        }

        return AudioTrack(
            attributes,
            format,
            minTrackBuf * 2,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
    }

    @Suppress("MissingPermission")
    fun startLoopback() {
        if (!hasMicPermission()) {
            listener?.onLoopbackError("مجوز میکروفون داده نشده")
            return
        }
        if (isRunning) {
            Log.d(TAG, "حلقه از قبل در حال اجراست")
            return
        }

        val minRecordBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, AUDIO_FORMAT)
        val minTrackBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, AUDIO_FORMAT)

        if (minRecordBuf <= 0 || minTrackBuf <= 0) {
            listener?.onLoopbackError("محاسبه بافر صدا ناموفق بود")
            return
        }

        val recordBufSize = (minRecordBuf * 1.5).toInt()

        // محاسبه‌ی تخمینی تاخیر: زمان لازم برای پر شدن بافر ضبط، دو برابر شده
        // (یک بار برای ضبط، یک بار برای پخش) — این فقط یک تخمین کلیه، نه یک
        // اندازه‌گیری دقیق.
        val bufferMs = (recordBufSize / 2.0 / SAMPLE_RATE * 1000).toInt()
        approxLatencyMs = bufferMs * 2

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_IN,
                AUDIO_FORMAT,
                recordBufSize
            )

            audioTrack = buildLowLatencyAudioTrack(minTrackBuf)
        } catch (e: Exception) {
            listener?.onLoopbackError("خطا در ساخت AudioRecord/AudioTrack: ${e.message}")
            release()
            return
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED ||
            audioTrack?.state != AudioTrack.STATE_INITIALIZED
        ) {
            listener?.onLoopbackError("مقداردهی اولیه AudioRecord یا AudioTrack ناموفق بود")
            release()
            return
        }

        audioRecord?.audioSessionId?.let { setupEchoCancellation(it) }

        forceOutputToSpeaker()

        isRunning = true
        audioRecord?.startRecording()
        audioTrack?.play()
        listener?.onLoopbackStarted()

        loopThread = Thread {
            val buffer = ShortArray(recordBufSize / 2)
            Log.d(TAG, "حلقه‌ی زنده شروع شد")

            while (isRunning) {
                val readCount = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (readCount > 0) {
                    audioTrack?.write(buffer, 0, readCount)
                }
            }
            Log.d(TAG, "حلقه‌ی زنده متوقف شد")
        }.apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stopLoopback() {
        isRunning = false
        loopThread?.join(500)
        loopThread = null
        release()
        listener?.onLoopbackStopped()
    }

    private fun release() {
        audioRecord?.apply {
            try { stop() } catch (e: IllegalStateException) { }
            release()
        }
        audioRecord = null

        echoCanceler?.apply {
            try { release() } catch (e: Exception) { }
        }
        echoCanceler = null

        audioTrack?.apply {
            try { stop() } catch (e: IllegalStateException) { }
            release()
        }
        audioTrack = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            audioManager.isSpeakerphoneOn = false
        }

        approxLatencyMs = 0

        Log.d(TAG, "منابع AudioRecord/AudioTrack کاملاً آزاد شدن")
    }

    fun isCurrentlyRunning(): Boolean = isRunning
}
