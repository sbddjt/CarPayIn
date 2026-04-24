package com.example.carpayin

import android.util.Log
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject

object MqttManager {
    private const val TAG = "MqttManager"

    // 서버 IP는 나중에 Ubuntu Public 서버 IP로 변경
    private const val BROKER_URL = "tcp://YOUR_SERVER_IP:1883"

    private var client: MqttClient? = null
    private var atcCounter = 0

    var onEntryNotification: ((lotId: String) -> Unit)? = null
    var onPaymentChallenge: ((amount: Int, nonce: String, merchantId: String, correlationId: String) -> Unit)? = null
    var onPaymentComplete: ((transactionId: String) -> Unit)? = null

    fun connect(uuid: String) {
        try {
            client = MqttClient(BROKER_URL, uuid, MemoryPersistence())
            val options = MqttConnectOptions().apply {
                isCleanSession = false
                keepAliveInterval = 60
                connectionTimeout = 10
                setWill(
                    "system/disconnect/$uuid",
                    "disconnected".toByteArray(),
                    0, false
                )
            }
            client?.connect(options)
            subscribeTopics(uuid)
            Log.d(TAG, "MQTT 연결 성공: $uuid")
        } catch (e: Exception) {
            Log.e(TAG, "MQTT 연결 실패: ${e.message}")
        }
    }

    private fun subscribeTopics(uuid: String) {
        // 입차 알림
        client?.subscribe("notification/$uuid", 0) { _, message ->
            val payload = JSONObject(String(message.payload))
            val lotId = payload.optString("lot_id", "주차장")
            Log.d(TAG, "입차 알림 수신: $lotId")
            onEntryNotification?.invoke(lotId)
        }

        // 결제 챌린지 (출차 시)
        client?.subscribe("payment/challenge/$uuid", 1) { _, message ->
            val payload = JSONObject(String(message.payload))
            Log.d(TAG, "결제 챌린지 수신: $payload")
            onPaymentChallenge?.invoke(
                payload.getInt("amount"),
                payload.getString("nonce"),
                payload.getString("merchant_id"),
                payload.getString("correlation_id")
            )
        }

        // 결제 완료 알림
        client?.subscribe("payment/complete/$uuid", 0) { _, message ->
            val payload = JSONObject(String(message.payload))
            val txId = payload.optString("transaction_id", "")
            Log.d(TAG, "결제 완료: $txId")
            onPaymentComplete?.invoke(txId)
        }
    }

    fun publishArqc(uuid: String, vin: String, amount: Int, nonce: String, correlationId: String) {
        try {
            atcCounter++
            val arqc = KeystoreManager.signArqc(vin, amount, nonce, correlationId, atcCounter)

            val payload = JSONObject().apply {
                put("arqc", arqc)
                put("atc", atcCounter)
                put("timestamp", java.util.Date().toString())
                put("correlation_id", correlationId)
            }.toString()

            val message = MqttMessage(payload.toByteArray()).apply { qos = 1 }
            client?.publish("payment/arqc/$uuid", message)
            Log.d(TAG, "ARQC 발행 완료 (ATC: $atcCounter)")
        } catch (e: Exception) {
            Log.e(TAG, "ARQC 발행 실패: ${e.message}")
        }
    }

    fun isConnected() = client?.isConnected == true

    fun disconnect() {
        try { client?.disconnect() } catch (e: Exception) { }
    }
}
