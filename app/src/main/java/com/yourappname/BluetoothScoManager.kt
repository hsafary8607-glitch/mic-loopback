package com.yourappname

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

class BluetoothScoManager(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

    private var scoReceiver: BluetoothScoReceiver? = null
    private var isReceiverRegistered = false

    interface ScoStatusListener {
        fun onScoConnected()
        fun onScoDisconnected()
        fun onScoError(message: String)
    }

    private var listener: ScoStatusListener? = null

    fun setStatusListener(listener: ScoStatusListener) {
        this.listener = listener
    }

    private fun hasBluetoothPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    fun startSco() {
        if (bluetoothAdapter == null) {
            listener?.onScoError("این گوشی بلوتوث نداره")
            return
        }
        if (!hasBluetoothPermission()) {
            listener?.onScoError("مجوز بلوتوث داده نشده")
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            listener?.onScoError("بلوتوث خاموش است")
            return
        }

        if (!isReceiverRegistered) {
            scoReceiver = BluetoothScoReceiver().apply {
                setStatusListener { connected -> onScoStatusChanged(connected) }
            }
            ContextCompat.registerReceiver(
                context,
                scoReceiver,
                android.content.IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            isReceiverRegistered = true
        }

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.startBluetoothSco()
        audioManager.isBluetoothScoOn = true

        Log.d(TAG, "درخواست شروع SCO ارسال شد، منتظر تایید سیستم...")
    }

    fun stopSco() {
        audioManager.stopBluetoothSco()
        audioManager.isBluetoothScoOn = false
        audioManager.mode = AudioManager.MODE_NORMAL

        if (isReceiverRegistered) {
            context.unregisterReceiver(scoReceiver)
            isReceiverRegistered = false
        }
        scoReceiver = null

        Log.d(TAG, "SCO متوقف شد")
    }

    private fun onScoStatusChanged(connected: Boolean) {
        if (connected) {
            listener?.onScoConnected()
            Log.d(TAG, "✅ SCO با موفقیت وصل شد (HFP)")
        } else {
            listener?.onScoDisconnected()
            Log.d(TAG, "❌ SCO قطع شد یا هنوز وصل نشده")
        }
    }

    companion object {
        private const val TAG = "BluetoothScoManager"
    }
}
