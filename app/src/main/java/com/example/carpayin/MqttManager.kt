package com.example.carpayin

import android.util.Log
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject

/**
 * MQTT 백그라운드 푸시 수신 매니저
 *
 * 새 흐름에서 MQTT 역할:
 *  ▸ 입차 확정 알림 수신 → EncryptedSharedPreferences parked 플래그 저장
 *  ▸ 결제 완료 알림 수신 → 거래 내역 저장 + parked 플래그 초기화
 *
 * 제거된 기능 (구 흐름):
 *  ✗ payment/challenge (결제 챌린지 → ARQC 서명)
 *  ✗ publishArqc       (ARQC 발행)
 *  → 결제는 앱이 백엔드 REST API에 직접 요청, 앱은 어떤 결제 키도 보유하지 않음
 *
 * 토픽 구조:
 *  parking/confirmed/{vin}   → 입차 확정 (Kafka Consumer 처리 완료)
 *  payment/complete/{vin}    → 결제 완료 (영수증 정보 포함)
 */
object MqttManager {
    private const val TAG = "MqttManager"

    // TODO: 실제 퍼블릭 백엔드 브로커 URL로 교체
    private const val BROKER_URL = "tcp://YOUR_SERVER_IP:1883"

    private var client: MqttClient? = null

    // ── 콜백 ─────────────────────────────────────────────────────────────────

    /**
     * 입차 확정 알림 수신
     * @param lotId     주차장 ID
     * @param sessionId 백엔드 파킹 세션 ID (EncryptedSharedPreferences 저장용)
     */
    var onParkingConfirmed: ((lotId: String, sessionId: String) -> Unit)? = null

    /**
     * 결제 완료 알림 수신
     * @param transactionId 거래 ID
     * @param approvalNumber 카드 승인번호
     * @param lotId 주차장 ID (영수증 표시용)
     * @param amount 결제 금액
     */
    var onPaymentComplete: ((
        transactionId: String,
        approvalNumber: String,
        lotId: String,
        amount: Int
    ) -> Unit)? = null

    // ── 연결 / 해제 ───────────────────────────────────────────────────────────

    /** 연결 끊김 감지 콜백 (CarPayInService 워치독이 재연결 트리거) */
    var onConnectionLost: ((cause: Throwable?) -> Unit)? = null

    fun connect(vin: String) {
        try {
            client = MqttClient(BROKER_URL, vin, MemoryPersistence())

            // 연결 끊김 / 메시지 수신 / 전송 완료 콜백
            client?.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    Log.w(TAG, "MQTT 연결 끊김: ${cause?.message}")
                    onConnectionLost?.invoke(cause)
                }
                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    // subscribe 시 IMqttMessageListener로 처리하므로 여기선 무시
                }
                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            val options = MqttConnectOptions().apply {
                isCleanSession = false
                keepAliveInterval = 60
                connectionTimeout = 10
                isAutomaticReconnect = false  // 워치독이 재연결 담당
                setWill(
                    "system/disconnect/$vin",
                    "disconnected".toByteArray(),
                    0, false
                )
            }
            client?.connect(options)
            subscribeTopics(vin)
            Log.d(TAG, "MQTT 연결 성공: VIN ${vin.take(8)}…")
        } catch (e: Exception) {
            Log.e(TAG, "MQTT 연결 실패: ${e.message}")
        }
    }

    private fun subscribeTopics(vin: String) {
        // ── 입차 확정 알림 (QoS 1: 적어도 1회 전달 보장) ────────────────────
        // 백엔드 Kafka Consumer가 Redis + PostgreSQL 저장 완료 후 발행
        // Payload: { "lot_id": "LOT_GANGNAM_01", "session_id": "ps_xxx", "lot_name": "강남 아이파킹" }
        client?.subscribe("parking/confirmed/$vin", 1) { _, message ->
            runCatching {
                val payload = JSONObject(String(message.payload))
                val lotId     = payload.optString("lot_id", "")
                val sessionId = payload.optString("session_id", "")
                Log.d(TAG, "입차 확정 수신: lot=$lotId, session=$sessionId")
                onParkingConfirmed?.invoke(lotId, sessionId)
            }.onFailure { Log.e(TAG, "입차 확정 파싱 오류: ${it.message}") }
        }

        // ── 결제 완료 알림 (QoS 1) ───────────────────────────────────────────
        // 백엔드 Kafka Consumer가 PostgreSQL 거래 내역 저장 + 아이파킹 paid 전달 완료 후 발행
        // Payload: { "transaction_id": "TX_...", "approval_number": "APPR_...",
        //            "lot_id": "LOT_...", "amount": 6000 }
        client?.subscribe("payment/complete/$vin", 1) { _, message ->
            runCatching {
                val payload      = JSONObject(String(message.payload))
                val txId         = payload.optString("transaction_id", "")
                val approvalNo   = payload.optString("approval_number", "")
                val lotId        = payload.optString("lot_id", "")
                val amount       = payload.optInt("amount", 0)
                Log.d(TAG, "결제 완료 수신: txId=$txId, lot=$lotId, amount=$amount")
                onPaymentComplete?.invoke(txId, approvalNo, lotId, amount)
            }.onFailure { Log.e(TAG, "결제 완료 파싱 오류: ${it.message}") }
        }
    }

    fun isConnected(): Boolean = client?.isConnected == true

    fun disconnect() {
        try { client?.disconnect() } catch (e: Exception) {}
    }
}
