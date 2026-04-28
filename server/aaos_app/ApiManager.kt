package com.example.carpayin

import android.util.Log
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * 백엔드 REST API 클라이언트 — 실서버 연동 버전
 *
 * 에뮬레이터에서 Windows localhost = 10.0.2.2
 * 실기기에서는 PC의 실제 IP 주소로 변경
 */
object ApiManager {
    private const val TAG = "ApiManager"

    const val BASE_URL = "http://10.0.2.2:8000"   // 에뮬레이터 → 호스트 PC 8000포트

    // ── 데이터 클래스 ─────────────────────────────────────────────────────────

    data class AuthResult(
        val accessToken: String,
        val refreshToken: String,
        val plateNumber: String
    )

    data class OrderResult(val orderId: String, val pgUrl: String)

    data class PaymentMethodResult(val paymentMethodId: String)

    data class FeeResult(
        val lotName: String,
        val durationMinutes: Int,
        val amount: Int
    )

    data class PaymentResult(
        val transactionId: String,
        val approvalNumber: String
    )

    data class TokenResult(
        val accessToken: String,
        val refreshToken: String
    )

    // ── 차량 인증 ─────────────────────────────────────────────────────────────

    fun authenticate(vin: String, certPem: String): AuthResult {
        // 1. 차량 등록 → 토큰 발급
        val regBody = JSONObject().apply {
            put("vin", vin)
            put("cert_hash", certPem.take(64))
        }.toString()
        val regRes = postJson("$BASE_URL/auth/register", regBody)

        val accessToken  = regRes.getString("access_token")
        val refreshToken = regRes.getString("refresh_token")

        // 2. Mock 국토부 API → 번호판 조회
        val plateRes    = getJson("$BASE_URL/auth/plate/$vin", accessToken)
        val plateNumber = plateRes.getString("plate")

        Log.d(TAG, "authenticate 완료 — VIN: ${vin.take(8)} plate: $plateNumber")
        return AuthResult(accessToken, refreshToken, plateNumber)
    }

    // ── 번호판 확인 ───────────────────────────────────────────────────────────

    fun confirmPlate(vin: String, plate: String, accessToken: String) {
        val body = JSONObject().apply {
            put("vin", vin)
            put("plate", plate)
        }.toString()
        postJson("$BASE_URL/auth/confirm-plate", body, accessToken)
        Log.d(TAG, "confirmPlate 완료 — $plate")
    }

    // ── 카드 등록 세션 생성 ───────────────────────────────────────────────────

    fun createCardRegistrationSession(vin: String, accessToken: String): OrderResult {
        val res     = getJson("$BASE_URL/card/order/$vin", accessToken)
        val orderId = res.getString("order_id")
        val pgUrl   = res.getString("pg_url")
        Log.d(TAG, "createCardRegistrationSession — order_id: $orderId")
        return OrderResult(orderId, pgUrl)
    }

    // ── payment_method_id 조회 (WebView 완료 후) ──────────────────────────────

    fun getPaymentMethodByOrderId(orderId: String, accessToken: String): PaymentMethodResult {
        val pmId = "pm_${orderId.takeLast(8)}"
        Log.d(TAG, "getPaymentMethodByOrderId — pm: $pmId")
        return PaymentMethodResult(pmId)
    }

    // ── 사전 알림 ─────────────────────────────────────────────────────────────

    fun sendPreNotification(
        vin: String,
        plate: String,
        lotId: String,
        triggerType: String,
        accessToken: String
    ) {
        val body = JSONObject().apply {
            put("vin", vin)
            put("plate", plate)
            put("lot_id", lotId)
            put("trigger", triggerType.lowercase())
        }.toString()
        postJson("$BASE_URL/pre-notify", body, accessToken)
        Log.d(TAG, "sendPreNotification 완료 — lot: $lotId trigger: $triggerType")
    }

    // ── 요금 조회 ─────────────────────────────────────────────────────────────

    fun queryFee(lotId: String, sessionId: String, accessToken: String): FeeResult {
        val res = getJson("$BASE_URL/fee/$sessionId", accessToken)
        return FeeResult(
            lotName         = res.getString("lot_name"),
            durationMinutes = res.getInt("duration_minutes"),
            amount          = res.getInt("amount")
        )
    }

    // ── 결제 요청 ─────────────────────────────────────────────────────────────

    fun requestPayment(sessionId: String, amount: Int, accessToken: String): PaymentResult {
        val body = JSONObject().apply {
            put("session_id", sessionId)
            put("amount", amount)
        }.toString()
        val res = postJson("$BASE_URL/payment", body, accessToken)
        return PaymentResult(
            transactionId  = res.getString("tx_id"),
            approvalNumber = res.getString("approval_no")
        )
    }

    // ── 토큰 갱신 ─────────────────────────────────────────────────────────────

    fun refreshToken(refreshToken: String): TokenResult {
        Thread.sleep(300)
        return TokenResult(
            accessToken  = "mock_access_${System.currentTimeMillis()}",
            refreshToken = "mock_refresh_${System.currentTimeMillis()}"
        )
    }

    // ── HTTP 유틸 ─────────────────────────────────────────────────────────────

    private fun postJson(urlStr: String, body: String, accessToken: String? = null): JSONObject {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            if (accessToken != null) setRequestProperty("Authorization", "Bearer $accessToken")
            doOutput        = true
            connectTimeout  = 5000
            readTimeout     = 10000
        }
        OutputStreamWriter(conn.outputStream).use { it.write(body) }
        if (conn.responseCode !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.readText() ?: "unknown"
            throw Exception("HTTP ${conn.responseCode}: $err")
        }
        return JSONObject(conn.inputStream.bufferedReader().readText())
    }

    private fun getJson(urlStr: String, accessToken: String? = null): JSONObject {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            if (accessToken != null) setRequestProperty("Authorization", "Bearer $accessToken")
            connectTimeout = 5000
            readTimeout    = 10000
        }
        if (conn.responseCode !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.readText() ?: "unknown"
            throw Exception("HTTP ${conn.responseCode}: $err")
        }
        return JSONObject(conn.inputStream.bufferedReader().readText())
    }
}
