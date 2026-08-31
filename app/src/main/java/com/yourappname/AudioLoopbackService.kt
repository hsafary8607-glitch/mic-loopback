package com.yourappname

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * مرحله ۷ — تغییرات نسبت به نسخه‌ی قبل (مرحله ۵):
 *   یک راه ساده اضافه شد که MainActivity بتونه وضعیت لحظه‌ای رو (وصل بودن
 *   بلوتوث، در حال پخش بودن یا نه، تاخیر تقریبی) بگیره و در صفحه نشون بده.
 *   هیچ چیز دیگه‌ای در منطق سرویس تغییر نکرده.
 */
class AudioLoopbackService : Service() {

    private lateinit var bluetoothScoManager: BluetoothScoManager
    private lateinit var audioLoopbackManager: AudioLoopbackManager
    private lateinit var notificationManager: NotificationManager

    private var isBluetoothConnected = false
    private var currentStatusText = "متوقف"

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        bluetoothScoManager = BluetoothScoManager(this)
        audioLoopbackManager = AudioLoopbackManager(this)

        bluetoothScoManager.setStatusListener(object : BluetoothScoManager.ScoStatusListener {
            override fun onScoConnected() {
                isBluetoothConnected = true
                audioLoopbackManager.startLoopback()
                pushStatus()
            }

            override fun onScoDisconnected() {
                isBluetoothConnected = false
                currentStatusText = "در انتظار اتصال ایرباد بلوتوث..."
                updateNotification(currentStatusText)
                pushStatus()
            }

            override fun onScoError(message: String) {
                currentStatusText = "خطا: $message"
                updateNotification(currentStatusText)
                pushStatus()
            }
        })

        audioLoopbackManager.setLoopbackListener(object : AudioLoopbackManager.LoopbackListener {
            override fun onLoopbackStarted() {
                currentStatusText = "در حال پخش زنده از اسپیکر گوشی"
                updateNotification(currentStatusText)
                pushStatus()
            }

            override fun onLoopbackStopped() {
                currentStatusText = "پخش متوقف شد"
                updateNotification(currentStatusText)
                pushStatus()
            }

            override fun onLoopbackError(message: String) {
                currentStatusText = "خطا: $message"
                updateNotification(currentStatusText)
                pushStatus()
            }
        })

        instance = this
        pushStatus()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart()
            ACTION_STOP -> handleStop()
        }
        return START_STICKY
    }

    private fun handleStart() {
        currentStatusText = "در حال اتصال به ایرباد..."
        val notification = buildNotification(currentStatusText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        bluetoothScoManager.startSco()
        pushStatus()
    }

    private fun handleStop() {
        audioLoopbackManager.stopLoopback()
        bluetoothScoManager.stopSco()
        isBluetoothConnected = false
        currentStatusText = "متوقف"
        stopForeground(STOP_FOREGROUND_REMOVE)
        pushStatus()
        stopSelf()
    }

    override fun onDestroy() {
        audioLoopbackManager.stopLoopback()
        bluetoothScoManager.stopSco()
        instance = null
        currentStatusText = "متوقف"
        isBluetoothConnected = false
        pushStatus()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "پخش زنده صدای میکروفون",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("پخش زنده میکروفون ایرباد")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(statusText: String) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    /** وضعیت فعلی رو به هر کسی که گوش می‌ده (یعنی MainActivity) اطلاع می‌ده. */
    private fun pushStatus() {
        uiListener?.onStatusUpdate(
            bluetoothConnected = isBluetoothConnected,
            loopbackRunning = audioLoopbackManager.isCurrentlyRunning(),
            statusText = currentStatusText,
            approxLatencyMs = audioLoopbackManager.approxLatencyMs
        )
    }

    companion object {
        const val ACTION_START = "com.yourappname.action.START_LOOPBACK"
        const val ACTION_STOP = "com.yourappname.action.STOP_LOOPBACK"

        private const val CHANNEL_ID = "audio_loopback_channel"
        private const val NOTIFICATION_ID = 1001

        // یک اشاره‌گر ساده به سرویس در حال اجرا، فقط برای اینکه MainActivity
        // بتونه وضعیت فعلی رو بخونه. چون سرویس و اکتیویتی هر دو داخل همون
        // اپ (همون پروسه) هستن، این روش کاملاً امن و ساده‌ست.
        private var instance: AudioLoopbackService? = null

        interface UiUpdateListener {
            fun onStatusUpdate(
                bluetoothConnected: Boolean,
                loopbackRunning: Boolean,
                statusText: String,
                approxLatencyMs: Int
            )
        }

        private var uiListener: UiUpdateListener? = null

        /** MainActivity این تابع رو صدا می‌زنه تا از تغییرات وضعیت باخبر بشه. */
        fun setUiListener(listener: UiUpdateListener?) {
            uiListener = listener
            instance?.pushStatus()
        }

        fun isRunning(): Boolean = instance != null

        fun start(context: Context) {
            val intent = Intent(context, AudioLoopbackService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AudioLoopbackService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
