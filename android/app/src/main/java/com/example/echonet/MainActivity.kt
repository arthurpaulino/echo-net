package com.example.echonet

import android.content.Context
import android.os.Bundle
import android.content.Intent
import android.content.SharedPreferences
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.echonet.ui.theme.EchoNetTheme

class MainActivity : ComponentActivity() {
    companion object {
        const val PREFS_SECTION = "settings"
        const val PREFS_VIBRATION_ENABLED = "vibration_enabled"
        const val PREFS_NOISE_THRESHOLD = "noise_threshold"

        const val DEFAULT_NOISE_THRESHOLD = 100f
        const val DEFAULT_VIBRATION_ENABLED = true
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var vibrator: Vibrator

    private val maxElapsed = MulticastService.CONNECTIVITY_WARNING_MS.toDouble()
    private val testVibrationEffect =
        VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS_SECTION, Context.MODE_PRIVATE)
        vibrator = MulticastService.getSystemVibrator(this)
        startForegroundService(Intent(this, MulticastService::class.java))
        setContent {
            EchoNetTheme {
                MainScreen()
            }
        }
    }

    override fun onDestroy() {
        stopService(Intent(this, MulticastService::class.java))
        super.onDestroy()
    }

    @Composable
    fun MainScreen() {
        var vibrationEnabled by remember {
            mutableStateOf(
                prefs.getBoolean(PREFS_VIBRATION_ENABLED, DEFAULT_VIBRATION_ENABLED)
            )
        }
        var noiseThreshold by remember {
            mutableFloatStateOf(
                prefs.getFloat(PREFS_NOISE_THRESHOLD, DEFAULT_NOISE_THRESHOLD)
            )
        }

        val elapsedTime by MulticastService.elapsedTime.collectAsState()
        val noise by MulticastService.noise.collectAsState()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Ruído",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(end = 8.dp)
                )

                val barHeight = 20.dp

                // Box to overlay noise bar & slider
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(barHeight)
                ) {
                    val noiseFraction = noise.toUByte().toFloat() / 255f

                    val barColor = when {
                        noiseFraction < 0.5f -> {
                            // Transition from Green to Yellow
                            val t = noiseFraction / 0.5f
                            Color(
                                red = t, green = 1f, blue = 0f
                            )
                        }

                        else -> {
                            // Transition from Yellow to Red
                            val t = (noiseFraction - 0.5f) / 0.5f
                            Color(
                                red = 1f, green = 1f - t, blue = 0f
                            )
                        }
                    }

                    // Noise Level Bar (Background)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(barHeight)
                            .background(Color.Gray.copy(alpha = 0.3f))
                    )

                    // Noise Level Indicator (Foreground)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction = noiseFraction)
                            .height(barHeight)
                            .background(barColor)
                    )

                    // Slider Over the Noise Bar
                    Slider(
                        value = noiseThreshold,
                        onValueChange = { noiseThreshold = it },
                        onValueChangeFinished = {
                            prefs.edit().putFloat(PREFS_NOISE_THRESHOLD, noiseThreshold).apply()
                        },
                        valueRange = 0f..255f,
                        steps = 254,
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.Center) // Keeps the slider in the middle of the bar
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            val shiftedElapsedTime =
                (elapsedTime - 1.1 * MulticastService.PACKETS_INTERVAL_MS).coerceAtLeast(
                    0.0
                )
            val connectivity = 100.0 * (1.0 - (shiftedElapsedTime / maxElapsed).coerceIn(
                0.0, 1.0
            ))
            Text(
                text = "Conectividade: ${"%.1f".format(connectivity)}%",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(12.dp))

            HorizontalDivider(
                color = Color.Gray, thickness = 1.dp, modifier = Modifier.fillMaxWidth()
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (vibrationEnabled) "Vibração LIGADA" else "Vibração DESLIGADA",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )

                Switch(checked = vibrationEnabled, onCheckedChange = {
                    vibrationEnabled = it
                    prefs.edit().putBoolean(PREFS_VIBRATION_ENABLED, vibrationEnabled).apply()
                    if (vibrationEnabled) {
                        vibrator.vibrate(testVibrationEffect)
                    }
                })
            }
        }
    }
}
