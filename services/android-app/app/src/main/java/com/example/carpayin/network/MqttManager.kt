package com.example.carpayin.network

import android.content.Context
import android.util.Log
import com.amazonaws.auth.CognitoCachingCredentialsProvider
import com.amazonaws.mobileconnectors.iot.AWSIotMqttClientStatusCallback.AWSIotMqttClientStatus
import com.amazonaws.mobileconnectors.iot.AWSIotMqttManager
import com.amazonaws.mobileconnectors.iot.AWSIotMqttQos
import com.amazonaws.regions.Regions
import com.example.carpayin.BuildConfig
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject

object MqttManager {
    private const val TAG = "MqttManager"
    private val IOT_ENDPOINT     get() = BuildConfig.IOT_ENDPOINT
    private val IDENTITY_POOL_ID get() = BuildConfig.COGNITO_IDENTITY_POOL_ID
    private val MOSQUITTO_URL    get() = BuildConfig.MOSQUITTO_BROKER_URL

    private var awsMqttManager: AWSIotMqttManager? = null
    private var pahoClient: MqttClient? = null
    private var connected = false

    var onParkingConfirmed: ((lotId: String, sessionId: String) -> Unit)? = null
    var onPaymentComplete: ((transactionId: String, approvalNumber: String, lotId: String, amount: Int) -> Unit)? = null
    var onConnectionLost: ((cause: Throwable?) -> Unit)? = null

    fun connect(context: Context, carId: String) {
        if (connected) return
        when {
            IOT_ENDPOINT.isNotEmpty()  -> connectAwsIot(context, carId)
            MOSQUITTO_URL.isNotEmpty() -> connectMosquitto(carId)
            else -> Log.d(TAG, "MQTT 미설정 — 연결 생략")
        }
    }

    private fun connectAwsIot(context: Context, carId: String) {
        if (awsMqttManager != null) return  // SDK 내부 reconnect 진행 중
        try {
            val credentialsProvider = CognitoCachingCredentialsProvider(
                context, IDENTITY_POOL_ID, Regions.AP_NORTHEAST_2
            )
            val clientId = "carpayin-${carId.takeLast(8)}"
            awsMqttManager = AWSIotMqttManager(clientId, IOT_ENDPOINT).apply {
                keepAlive = 60
                isAutoReconnect = true
                maxAutoReconnectAttempts = -1
            }
            awsMqttManager!!.connect(credentialsProvider) { status, throwable ->
                when (status) {
                    AWSIotMqttClientStatus.Connected -> {
                        Log.d(TAG, "IoT Core 연결 성공")
                        connected = true
                        subscribeAwsTopics(carId)
                    }
                    AWSIotMqttClientStatus.ConnectionLost -> {
                        Log.w(TAG, "IoT Core 연결 끊김 - SDK 자동 재연결 대기")
                        connected = false
                        onConnectionLost?.invoke(throwable)
                    }
                    AWSIotMqttClientStatus.Reconnecting -> {
                        Log.d(TAG, "IoT Core 재연결 중...")
                        connected = false
                    }
                    else -> Log.d(TAG, "IoT Core 상태: $status")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "IoT Core 연결 실패: ${e.message}")
            awsMqttManager = null
        }
    }

    private fun connectMosquitto(carId: String) {
        try {
            val clientId = "android-${carId.takeLast(8)}-${System.currentTimeMillis() % 100000}"
            val client = MqttClient(MOSQUITTO_URL, clientId, MemoryPersistence())
            val options = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 10
                keepAliveInterval = 60
                isAutomaticReconnect = true
            }
            client.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    Log.d(TAG, "Mosquitto ${if (reconnect) "재연결" else "연결"} 완료: $serverURI")
                    connected = true
                    if (reconnect) subscribeMosquittoTopics(client, carId)
                }
                override fun connectionLost(cause: Throwable?) {
                    Log.w(TAG, "Mosquitto 연결 끊김: ${cause?.message}")
                    connected = false
                    onConnectionLost?.invoke(cause)
                }
                override fun messageArrived(topic: String, message: MqttMessage) {
                    handleMessage(topic, message.payload)
                }
                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })
            client.connect(options)
            pahoClient = client
            connected = true
            subscribeMosquittoTopics(client, carId)
            Log.d(TAG, "Mosquitto 연결 성공: $MOSQUITTO_URL")
        } catch (e: Exception) {
            Log.e(TAG, "Mosquitto 연결 실패: ${e.message}")
            pahoClient = null
            connected = false
        }
    }

    private fun subscribeAwsTopics(carId: String) {
        awsMqttManager?.subscribeToTopic("parking/confirmed/$carId", AWSIotMqttQos.QOS1) { _, data ->
            handleMessage("parking/confirmed/$carId", data)
        }
        awsMqttManager?.subscribeToTopic("payment/complete/$carId", AWSIotMqttQos.QOS1) { _, data ->
            handleMessage("payment/complete/$carId", data)
        }
    }

    private fun subscribeMosquittoTopics(client: MqttClient, carId: String) {
        client.subscribe("parking/confirmed/$carId", 1)
        client.subscribe("payment/complete/$carId", 1)
        Log.d(TAG, "Mosquitto 구독 완료: parking/confirmed/$carId, payment/complete/$carId")
    }

    private fun handleMessage(topic: String, data: ByteArray) {
        runCatching {
            val payload = JSONObject(String(data))
            when {
                topic.startsWith("parking/confirmed/") -> {
                    val lotId     = payload.optString("lot_id", "")
                    val sessionId = payload.optString("session_id", "")
                    Log.d(TAG, "입차 확정 수신: lot=$lotId")
                    onParkingConfirmed?.invoke(lotId, sessionId)
                }
                topic.startsWith("payment/complete/") -> {
                    val txId       = payload.optString("transaction_id", "")
                    val approvalNo = payload.optString("approval_number", "")
                    val lotId      = payload.optString("lot_id", "")
                    val amount     = payload.optInt("amount", 0)
                    Log.d(TAG, "결제 완료 수신: tx=$txId, amount=$amount")
                    onPaymentComplete?.invoke(txId, approvalNo, lotId, amount)
                }
            }
        }.onFailure { Log.e(TAG, "MQTT 메시지 파싱 오류: ${it.message}") }
    }

    fun isConnected(): Boolean = connected

    fun isAlive(): Boolean =
        awsMqttManager != null || (pahoClient != null && pahoClient!!.isConnected)

    fun disconnect() {
        try {
            awsMqttManager?.disconnect()
            pahoClient?.disconnect()
            connected = false
            Log.d(TAG, "MQTT 연결 해제")
        } catch (e: Exception) {
            Log.w(TAG, "MQTT 해제 오류: ${e.message}")
        } finally {
            awsMqttManager = null
            pahoClient = null
        }
    }
}
