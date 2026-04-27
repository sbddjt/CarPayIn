package com.example.carpayin

import android.util.Log
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * 백엔드 REST API 클라이언트
 *
 * ▸ 모든 public 메서드는 백그라운드 스레드에서 호출해야 합니다.
 * ▸ TODO 주석이 달린 블록은 실서버 연동 시 교체할 부분입니다.
 * ▸ Mock 구현은 실제 흐름과 동일하게 지연(Thread.sleep)을 포함합니다.
 */
object ApiManager {
    private const val TAG = "ApiManager"

    // TODO: 실제 퍼블릭 백엔드 서버 URL로 교체
    private const val BASE_URL = "https://YOUR_BACKEND_URL"

    // ── 데이터 클래스 ─────────────────────────────────────────────────────────

    /** 차량 인증 결과: 액세스/리프레시 토큰 + 번호판 */
    data class AuthResult(
        val accessToken: String,
        val refreshToken: String,
        val plateNumber: String      // Mock 국토부 API로 조회된 번호판
    )

    /** 카드 등록 세션 결과 */
    data class OrderResult(val orderId: String)

    /** 카드 등록 완료 결과 */
    data class PaymentMethodResult(val paymentMethodId: String)

    /** 현재 주차 요금 조회 결과 */
    data class FeeResult(
        val lotName: String,
        val durationMinutes: Int,
        val amount: Int
    )

    /** 결제 처리 결과 */
    data class PaymentResult(
        val transactionId: String,
        val approvalNumber: String
    )

    /** 토큰 갱신 결과 */
    data class TokenResult(
        val accessToken: String,
        val refreshToken: String
    )

    // ── 차량 인증 (mTLS + VIN) → 액세스 토큰 + 리프레시 토큰 + 번호판 ────────
    /**
     * 1. VIN + 앱 인증서(PEM)를 mTLS로 퍼블릭 백엔드에 전송
     * 2. 백엔드: VIN + 인증서 지문 DB 저장 → Mock 국토부 API 번호판 조회
     * 3. 반환: access_token(1h) + refresh_token(30d) + plate_number
     */
    fun authenticate(vin: String, certPem: String): AuthResult {
        // TODO: mTLS 실구현
        // val sslContext = buildMtlsSslContext()   // KeystoreManager.buildSslContext() 활용
        // val url = URL("$BASE_URL/v1/auth/vehicle")
        // val body = JSONObject().apply {
        //     put("vin", vin)
        //     put("cert_pem", certPem)
        // }.toString()
        // val response = postJson(url, body, sslContext = sslContext)
        // return AuthResult(
        //     accessToken  = response.getString("access_token"),
        //     refreshToken = response.getString("refresh_token"),
        //     plateNumber  = response.getString("plate_number")
        // )

        Thread.sleep(1500)
        Log.d(TAG, "[Mock] authenticate — VIN: ${vin.take(8)}…")
        return AuthResult(
            accessToken  = "mock_access_${System.currentTimeMillis()}",
            refreshToken = "mock_refresh_${System.currentTimeMillis()}",
            plateNumber  = "123가4567"
        )
    }

    // ── 번호판 확인 → 백엔드 DB(VIN ↔ 번호판) 저장 ──────────────────────────
    fun confirmPlate(vin: String, plate: String, accessToken: String) {
        // TODO: POST $BASE_URL/v1/vehicles/plate
        // val body = JSONObject().apply {
        //     put("vin", vin)
        //     put("plate_number", plate)
        // }.toString()
        // postJson(URL("$BASE_URL/v1/vehicles/plate"), body, accessToken)

        Thread.sleep(500)
        Log.d(TAG, "[Mock] confirmPlate — 번호판 '$plate' 확인 완료, DB 저장")
    }

    // ── 카드 등록 세션 생성 → order_id (Redis TTL 30분) ──────────────────────
    /**
     * 백엔드가 order_id를 생성하고 Redis에 { order_id → VIN } 저장 (TTL 30분)
     */
    fun createCardRegistrationSession(vin: String, accessToken: String): OrderResult {
        // TODO: POST $BASE_URL/v1/payment/session
        // val body = JSONObject().apply { put("vin", vin) }.toString()
        // val response = postJson(URL("$BASE_URL/v1/payment/session"), body, accessToken)
        // return OrderResult(orderId = response.getString("order_id"))

        Thread.sleep(500)
        val orderId = "order_${System.currentTimeMillis()}"
        Log.d(TAG, "[Mock] createCardRegistrationSession — order_id: $orderId")
        return OrderResult(orderId = orderId)
    }

    // ── Mock PG WebView 완료 후 payment_method_id 폴링 ───────────────────────
    /**
     * WebView 카드 등록 완료 리다이렉트 URL에서 payment_method_id 추출.
     * 실제로는 백엔드가 웹훅(HMAC 검증 → Redis order_id→VIN → DB 저장)을 처리한 뒤
     * payment_method_id를 반환.
     */
    fun getPaymentMethodByOrderId(orderId: String, accessToken: String): PaymentMethodResult {
        // TODO: GET $BASE_URL/v1/payment/session/$orderId/result
        // val response = getJson(URL("$BASE_URL/v1/payment/session/$orderId/result"), accessToken)
        // return PaymentMethodResult(paymentMethodId = response.getString("payment_method_id"))

        Thread.sleep(800)
        val pmId = "pm_${orderId.takeLast(8)}"
        Log.d(TAG, "[Mock] getPaymentMethodByOrderId — payment_method_id: $pmId")
        return PaymentMethodResult(paymentMethodId = pmId)
    }

    // ── 사전 알림 전송 ────────────────────────────────────────────────────────
    /**
     * 지오펜스 진입 or 내비 목적지 설정 시 백엔드에 사전 알림 전송.
     * 백엔드: Redis incoming(TTL 1h) + 아이파킹 PMS 번호판 사전 등록
     *
     * @param triggerType "GEOFENCE" | "NAVI"
     */
    fun sendPreNotification(
        vin: String,
        plate: String,
        lotId: String,
        triggerType: String,
        accessToken: String
    ) {
        // TODO: POST $BASE_URL/v1/parking/pre-notify
        // val body = JSONObject().apply {
        //     put("vin", vin)
        //     put("plate_number", plate)
        //     put("lot_id", lotId)
        //     put("trigger_type", triggerType)
        // }.toString()
        // postJson(URL("$BASE_URL/v1/parking/pre-notify"), body, accessToken)

        Thread.sleep(300)
        Log.d(TAG, "[Mock] sendPreNotification — lot: $lotId, trigger: $triggerType")
    }

    // ── 현재 주차 요금 조회 ───────────────────────────────────────────────────
    /**
     * 시동 ON + parked=true 시 호출.
     * 백엔드: Redis 우선 조회 → miss 시 PostgreSQL fallback → 아이파킹 API 요금 계산
     */
    fun queryFee(lotId: String, sessionId: String, accessToken: String): FeeResult {
        // TODO: GET $BASE_URL/v1/parking/fee?lot_id=$lotId&session_id=$sessionId
        // val response = getJson(URL("$BASE_URL/v1/parking/fee?lot_id=$lotId&session_id=$sessionId"), accessToken)
        // return FeeResult(
        //     lotName         = response.getString("lot_name"),
        //     durationMinutes = response.getInt("duration_minutes"),
        //     amount          = response.getInt("amount")
        // )

        Thread.sleep(800)
        Log.d(TAG, "[Mock] queryFee — lot: $lotId, session: $sessionId")
        return FeeResult(
            lotName         = if (lotId.isNotEmpty()) lotId else "강남 아이파킹",
            durationMinutes = 200,
            amount          = 6000
        )
    }

    // ── 결제 요청 ─────────────────────────────────────────────────────────────
    /**
     * 앱은 어떤 카드 키도 갖지 않음. 백엔드가 VIN → customer_key → Mock PG → 카드 승인 처리.
     * idempotency_key: session_id + plate + amount + timestamp (이중 결제 방지)
     */
    fun requestPayment(
        sessionId: String,
        amount: Int,
        accessToken: String
    ): PaymentResult {
        // TODO: POST $BASE_URL/v1/payment/charge
        // val body = JSONObject().apply {
        //     put("session_id", sessionId)
        //     put("amount", amount)
        // }.toString()
        // val response = postJson(URL("$BASE_URL/v1/payment/charge"), body, accessToken)
        // return PaymentResult(
        //     transactionId  = response.getString("transaction_id"),
        //     approvalNumber = response.getString("approval_number")
        // )

        Thread.sleep(2500)   // 카드 승인 처리 시간 시뮬레이션 (VPN + OpenStack 포함)
        val txId   = "TX_${System.currentTimeMillis()}"
        val apprNo = "APPR_${(100000..999999).random()}"
        Log.d(TAG, "[Mock] requestPayment — txId: $txId, approval: $apprNo")
        return PaymentResult(transactionId = txId, approvalNumber = apprNo)
    }

    // ── 토큰 갱신 ─────────────────────────────────────────────────────────────
    /**
     * 리프레시 토큰으로 새 액세스 토큰 + 리프레시 토큰 발급.
     * 액세스 토큰 만료(1시간) 5분 전에 CarPayInService에서 자동 호출됩니다.
     */
    fun refreshToken(refreshToken: String): TokenResult {
        // TODO: POST $BASE_URL/v1/auth/refresh
        // val body = JSONObject().apply { put("refresh_token", refreshToken) }.toString()
        // val response = postJson(URL("$BASE_URL/v1/auth/refresh"), body)
        // return TokenResult(
        //     accessToken  = response.getString("access_token"),
        //     refreshToken = response.getString("refresh_token")
        // )

        Thread.sleep(500)
        Log.d(TAG, "[Mock] refreshToken — 토큰 갱신 완료")
        return TokenResult(
            accessToken  = "mock_access_${System.currentTimeMillis()}",
            refreshToken = "mock_refresh_${System.currentTimeMillis()}"
        )
    }

    // ── 내부 HTTP 유틸 (실서버 연동 시 활성화) ───────────────────────────────

    @Suppress("unused")
    private fun postJson(
        url: URL,
        body: String,
        accessToken: String? = null
    ): JSONObject {
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            if (accessToken != null) conn.setRequestProperty("Authorization", "Bearer $accessToken")
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            OutputStreamWriter(conn.outputStream).use { it.write(body) }
            val response = conn.inputStream.bufferedReader().readText()
            JSONObject(response)
        } finally {
            conn.disconnect()
        }
    }

    @Suppress("unused")
    private fun getJson(url: URL, accessToken: String? = null): JSONObject {
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            if (accessToken != null) conn.setRequestProperty("Authorization", "Bearer $accessToken")
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            val response = conn.inputStream.bufferedReader().readText()
            JSONObject(response)
        } finally {
            conn.disconnect()
        }
    }
}
