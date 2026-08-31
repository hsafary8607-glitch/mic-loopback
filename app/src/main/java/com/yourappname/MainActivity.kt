package com.yourappname

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * مرحله ۷ — رابط کاربری نهایی
 *
 * این صفحه شامل:
 *   - یک دکمه‌ی شروع/توقف
 *   - یک خط وضعیت اتصال بلوتوث
 *   - یک خط وضعیت پخش (در حال اجرا / متوقف / خطا)
 *   - یک خط تاخیر تقریبی (فقط وقتی در حال اجراست نمایش داده می‌شه)
 *
 * این فایل جای MainActivity.kt قبلی رو می‌گیره (همون مسیر، همون اسم).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var buttonStartStop: Button
    private lateinit var textBluetoothStatus: TextView
    private lateinit var textLoopbackStatus: TextView
    private lateinit var textLatency: TextView

    // آیا در حال حاضر کاربر روی «شروع» زده؟ (برای وضعیت دکمه)
    private var isServiceRequested = false

    // درخواست مجوزهای لازم (میکروفون، و بلوتوث در اندروید ۱۲ به بالا)
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            startLoopbackService()
        } else {
            showError("بدون اجازه‌ی میکروفون و بلوتوث، اپ نمی‌تونه کار کنه. لطفاً از تنظیمات گوشی مجوزها رو فعال کن.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        buttonStartStop = findViewById(R.id.buttonStartStop)
        textBluetoothStatus = findViewById(R.id.textBluetoothStatus)
        textLoopbackStatus = findViewById(R.id.textLoopbackStatus)
        textLatency = findViewById(R.id.textLatency)

        buttonStartStop.setOnClickListener {
            if (isServiceRequested) {
                stopLoopbackService()
            } else {
                requestPermissionsAndStart()
            }
        }

        // اگه سرویس از قبل (مثلاً قبل از چرخش صفحه) در حال اجرا بوده، وضعیت رو بازیابی کن
        isServiceRequested = AudioLoopbackService.isRunning()
        updateButtonText()
    }

    override fun onStart() {
        super.onStart()
        // از این لحظه به بعد، هر تغییر وضعیتی که سرویس بده رو دریافت می‌کنیم
        AudioLoopbackService.setUiListener(object : AudioLoopbackService.Companion.UiUpdateListener {
            override fun onStatusUpdate(
                bluetoothConnected: Boolean,
                loopbackRunning: Boolean,
                statusText: String,
                approxLatencyMs: Int
            ) {
                runOnUiThread {
                    textBluetoothStatus.text = "بلوتوث: " + if (bluetoothConnected) "وصل ✅" else "وصل نیست ❌"
                    textLoopbackStatus.text = "وضعیت: $statusText"
                    textLatency.text = if (loopbackRunning && approxLatencyMs > 0) {
                        "تاخیر تقریبی: حدود $approxLatencyMs میلی‌ثانیه"
                    } else {
                        "تاخیر تقریبی: —"
                    }
                    isServiceRequested = loopbackRunning || AudioLoopbackService.isRunning()
                    updateButtonText()
                }
            }
        })
    }

    override fun onStop() {
        super.onStop()
        // وقتی صفحه دیده نمی‌شه، دیگه لازم نیست آپدیت بگیریم (خود سرویس همچنان در پس‌زمینه کار می‌کنه)
        AudioLoopbackService.setUiListener(null)
    }

    private fun requestPermissionsAndStart() {
        val neededPermissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            neededPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        val notGranted = neededPermissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) {
            startLoopbackService()
        } else {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun startLoopbackService() {
        AudioLoopbackService.start(this)
        isServiceRequested = true
        textLoopbackStatus.text = "وضعیت: در حال اتصال به ایرباد..."
        updateButtonText()
    }

    private fun stopLoopbackService() {
        AudioLoopbackService.stop(this)
        isServiceRequested = false
        textBluetoothStatus.text = "بلوتوث: وصل نیست ❌"
        textLoopbackStatus.text = "وضعیت: متوقف"
        textLatency.text = "تاخیر تقریبی: —"
        updateButtonText()
    }

    private fun updateButtonText() {
        buttonStartStop.text = if (isServiceRequested) "توقف" else "شروع"
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        textLoopbackStatus.text = "وضعیت: خطا"
    }
}
