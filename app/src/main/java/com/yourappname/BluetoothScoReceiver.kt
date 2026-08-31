package com.yourappname

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class BluetoothScoReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var statusListener: ((connected: Boolean) -> Unit)? = null

    fun setStatusListener(listener: (connected: Boolean) -> Unit) {
        statusListener = listener
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) {
            val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
            val connected = state == AudioManager.SCO_AUDIO_STATE_CONNECTED

            scope.launch {
                delay(100)
                statusListener?.invoke(connected)
                Log.d(TAG, "SCO وضعیت: ${if (connected) "CONNECTED" else "DISCONNECTED"} (کد: $state)")
            }
        }
    }

    companion object {
        private const val TAG = "BluetoothScoReceiver"
    }
}
