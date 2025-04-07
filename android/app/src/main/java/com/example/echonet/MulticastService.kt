package com.example.echonet

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.net.wifi.WifiManager.MulticastLock
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException

class MulticastService : Service() {
    companion object {
        const val PACKETS_INTERVAL_MS = 1_000L
        const val CONNECTIVITY_WARNING_MS = 10_000L

        private val _elapsedTime = MutableStateFlow(CONNECTIVITY_WARNING_MS)
        val elapsedTime: StateFlow<Long> = _elapsedTime.asStateFlow()

        private val _noise = MutableStateFlow<Byte>(0)
        val noise: StateFlow<Byte> = _noise.asStateFlow()

        fun getSystemVibrator(context: Context): Vibrator {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager =
                    context.getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
                return vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION") return context.getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
        }
    }

    private val multicastAddress = "224.0.0.1"
    private val multicastPort = 5000

    private val highNoiseVibrationEffect =
        VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE)
    private val lowConnectivityVibrationEffect =
        VibrationEffect.createWaveform(longArrayOf(0, 150, 100, 150), -1)

    private var lastPacketTime = SystemClock.elapsedRealtime()

    // The following variables should be initialized from first to last and canceled/released from
    // last to first
    private lateinit var prefs: SharedPreferences
    private lateinit var vibrator: Vibrator
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var multicastLock: MulticastLock
    private lateinit var multicastListener: Job
    private lateinit var timeoutChecker: Job

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundService()
        prefs = getSharedPreferences(MainActivity.PREFS_SECTION, MODE_PRIVATE)
        vibrator = getSystemVibrator(this)
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        acquireMulticastLock()
        initMulticastListener()
        initTimeoutChecker()
        return START_NOT_STICKY
    }

    private fun startForegroundService() {
        val channelId = "MulticastServiceChannel"

        val channel = NotificationChannel(
            channelId, "Multicast Service", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)

        val notification =
            NotificationCompat.Builder(this, channelId).setContentTitle("EchoNet está em execução")
                .setOngoing(true) // Prevents user from swiping it away
                .build()

        ServiceCompat.startForeground(
            this, 1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
    }

    private fun acquireMulticastLock() {
        val wifiManager = getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("EchoNetMulticastLock").apply {
            setReferenceCounted(true)
            acquire()
        }
    }

    private fun initMulticastListener() {
        multicastListener = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                if (!isWifiConnected()) {
                    Log.w("MulticastService", "WiFi is not connected. Waiting...")
                    delay(2000)
                    continue
                }
                try {
                    MulticastSocket(multicastPort).use { socket ->
                        val group = InetAddress.getByName(multicastAddress)
                        socket.joinGroup(group)
                        socket.soTimeout = 5000 // Timeout to detect disconnects

                        val buffer = ByteArray(1)
                        while (isActive) {
                            try {
                                val packet = DatagramPacket(buffer, buffer.size)
                                socket.receive(packet) // Blocks until data arrives or timeout

                                lastPacketTime = SystemClock.elapsedRealtime()
                                val noise = packet.data[0]
                                val noiseThreshold = prefs.getFloat(
                                    MainActivity.PREFS_NOISE_THRESHOLD,
                                    MainActivity.DEFAULT_NOISE_THRESHOLD
                                ).toInt().toUByte()
                                if (noise.toUByte() >= noiseThreshold) {
                                    vibratePhone(highNoiseVibrationEffect)
                                }
                                _noise.value = noise
                            } catch (e: SocketTimeoutException) {
                                break
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MulticastService", "Failed to start multicast listener: ${e.message}")
                }
            }
        }
    }

    private fun isWifiConnected(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun initTimeoutChecker() {
        timeoutChecker = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                delay(PACKETS_INTERVAL_MS)
                val elapsed = SystemClock.elapsedRealtime() - lastPacketTime
                if (elapsed > CONNECTIVITY_WARNING_MS) {
                    vibratePhone(lowConnectivityVibrationEffect)
                }
                _elapsedTime.value = elapsed
            }
        }
    }

    private fun vibratePhone(effect: VibrationEffect) {
        if (!prefs.getBoolean(
                MainActivity.PREFS_VIBRATION_ENABLED, MainActivity.DEFAULT_VIBRATION_ENABLED
            )
        ) return
        vibrator.vibrate(effect)
    }

    override fun onDestroy() {
        timeoutChecker.cancel()
        multicastListener.cancel()
        multicastLock.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
