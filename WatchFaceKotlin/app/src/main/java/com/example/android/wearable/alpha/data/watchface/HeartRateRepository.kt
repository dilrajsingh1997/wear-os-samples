package com.example.android.wearable.alpha.data.watchface

import android.content.Context
import android.os.SystemClock
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerCallback
import androidx.health.services.client.PassiveMonitoringClient
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import com.example.android.wearable.alpha.utils.Logger

class HeartRateRepository(
    context: Context
) {
    var lastCallbackTime: Long? = -1L

    val isRecentCallbackReceived: Boolean?
        get() = lastCallbackTime?.let { (SystemClock.elapsedRealtime() - it) < 2_000L }

    var heartRate = 0.0
        private set(value) {
            if (value != 0.0) {
                lastCallbackTime = SystemClock.elapsedRealtime()
                field = value
            } else {
                lastCallbackTime = null
            }
        }

    private val passiveListenerConfig = PassiveListenerConfig.builder()
        .setDataTypes(setOf(DataType.HEART_RATE_BPM))
//        .setHealthEventTypes(setOf(HealthEvent.Type.FALL_DETECTED))
        .build()

    private val passiveListenerCallback: PassiveListenerCallback =
        object : PassiveListenerCallback {
            override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
                Logger.d("debugdilrajhr", "data received passive callback " +
                    dataPoints.intervalDataPoints.joinToString { it.value.toString() } +
                    " " +
                    dataPoints.getData(DataType.HEART_RATE_BPM)
                        .joinToString { it.value.toString() })
                runCatching {
                    heartRate = dataPoints
                        .getData(DataType.HEART_RATE_BPM)
                        .last()
                        .value
                }
            }
        }

    private var passiveMonitoringClient: PassiveMonitoringClient

    init {
        val healthClient = HealthServices.getClient(context)
        passiveMonitoringClient = healthClient.passiveMonitoringClient
        passiveMonitoringClient.setPassiveListenerCallback(
            passiveListenerConfig,
            passiveListenerCallback
        )
    }

    fun onDestroy() {
        passiveMonitoringClient.clearPassiveListenerCallbackAsync()
    }
}
