package com.example.carpayin

import android.util.Log
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object ApiManager {
    private const val TAG = "ApiManager"

    // Android 에뮬레이터에서 호스트 PC의 localhost = 10.0.2.2
    private const val BASE_URL = "http://10.0.2.2:8080"

    // ── 데이터 클래스 ─────────────────────────────────────────────────────────

    data class AuthResult(
        val accessToken: String,
        val refreshToken: String,
        val plateNumber: String
    )

    data class HyundaiAuthResult(
        val accessToken: String,
        val refreshToken: String,
        val plateNumber: String,
        val vinList: List<VinInfo>,
        val userId: String
    )

    data class VinInfo(
        val vin: String,
        val carId: String,
        val modelName: String,
        val year: Int
    )

    data class ParkingLotInfo(val id: String, val name: String, val lat: Double, val lng: Double)
    data class OrderResult(val orderId: String)
    data class PaymentMethodResult(val paymentMethodId: String)
    data class FeeResult(val lotName: String, val durationMinutes: Int, val amount: Int)
    data class PaymentResult(val transactionId: String, val approvalNumber: String)
    data class TokenResult(val accessToken: String, val refreshToken: String)

    // ── 기존 API 함수들 ───────────────────────────────────────────────────────

    fun authenticateWithHyundai(authCode: String, vin: String, certHash: String): HyundaiAuthResult {
        val body = JSONObject().apply {
            put("code", authCode)
            put("vin", vin)
            put("cert_hash", certHash)
        }.toString()
        val response = postJson(URL("$BASE_URL/auth/hyundai/callback"), body)
        val vinArray = response.getJSONArray("vin_list")
        val vinList = (0 until vinArray.length()).map { i ->
            val item = vinArray.getJSONObject(i)
            VinInfo(item.getString("vin"), item.getString("car_id"), item.optString("model_name", ""), item.optInt("year", 0))
        }
        return HyundaiAuthResult(response.getString("access_token"), response.getString("refresh_token"), response.getString("plate_number"), vinList, response.getString("user_id"))
    }

    fun confirmVin(vin: String, carId: String, accessToken: String) {
        val body = JSONObject().apply { put("vin", vin); put("car_id", carId) }.toString()
        postJson(URL("$BASE_URL/auth/confirm-vin"), body, accessToken)
    }

    fun authenticate(vin: String, certPem: String): AuthResult {
        val regBody = JSONObject().apply { put("vin", vin); put("cert_hash", certPem.take(64).ifEmpty { "MOCK_CERT_HASH" }) }.toString()
        val regRes = postJson(URL("$BASE_URL/auth/register"), regBody)
        val plateRes = getJson(URL("$BASE_URL/auth/plate/$vin"))
        return AuthResult(regRes.getString("access_token"), regRes.getString("refresh_token"), plateRes.getString("plate"))
    }

    fun confirmPlate(vin: String, plate: String, accessToken: String) {
        val body = JSONObject().apply { put("vin", vin); put("plate", plate) }.toString()
        postJson(URL("$BASE_URL/auth/confirm-plate"), body, accessToken)
    }

    fun createCardRegistrationSession(vin: String, accessToken: String): OrderResult {
        val response = getJson(URL("$BASE_URL/card/order/$vin"), accessToken)
        return OrderResult(orderId = response.getString("order_id"))
    }

    fun getPaymentMethodByOrderId(orderId: String, accessToken: String): PaymentMethodResult {
        Thread.sleep(800)
        return PaymentMethodResult(paymentMethodId = "pm_${orderId.takeLast(12)}")
    }

    fun fetchParkingLots(): List<ParkingLotInfo> {
        return try {
            val response = getJson(URL("$BASE_URL/parking/lots"))
            val arr = response.optJSONArray("lots") ?: return emptyList()
            (0 until arr.length()).map { i ->
                val item = arr.getJSONObject(i)
                ParkingLotInfo(item.getString("id"), item.getString("name"), item.getDouble("lat"), item.getDouble("lng"))
            }
        } catch (e: Exception) {
            listOf(
                ParkingLotInfo("LOT_GN_01", "강남 CarPayIn 주차장", 37.4979, 127.0276),
                ParkingLotInfo("LOT_HD_01", "홍대 CarPayIn 주차장", 37.5567, 126.9236)
            )
        }
    }

    fun sendPreNotification(vin: String, plate: String, lotId: String, triggerType: String, accessToken: String) {
        val body = JSONObject().apply { put("vin", vin); put("plate", plate); put("lot_id", lotId); put("trigger", triggerType.lowercase()) }.toString()
        postJson(URL("$BASE_URL/pre-notify"), body, accessToken)
    }

    fun queryFee(lotId: String, sessionId: String, accessToken: String): FeeResult {
        val response = getJson(URL("$BASE_URL/fee/$sessionId"), accessToken)
        return FeeResult(response.optString("lot_name", lotId), response.optInt("duration_minutes", 0), response.getInt("amount"))
    }

    fun requestPayment(sessionId: String, amount: Int, accessToken: String): PaymentResult {
        val body = JSONObject().apply { put("session_id", sessionId); put("amount", amount) }.toString()
        val response = postJson(URL("$BASE_URL/payment"), body, accessToken)
        return PaymentResult(response.getString("tx_id"), response.getString("approval_no"))
    }

    fun refreshToken(refreshToken: String): TokenResult {
        return TokenResult(accessToken = refreshToken, refreshToken = refreshToken)
    }

    // ── 내부 HTTP 유틸 ────────────────────────────────────────────────────────

    private fun postJson(url: URL, body: String, accessToken: String? = null): JSONObject {
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
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val response = stream?.bufferedReader()?.readText() ?: "{}"
            if (code !in 200..299) throw RuntimeException("HTTP $code: $response")
            JSONObject(response)
        } finally {
            conn.disconnect()
        }
    }

    private fun getJson(url: URL, accessToken: String? = null): JSONObject {
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            if (accessToken != null) conn.setRequestProperty("Authorization", "Bearer $accessToken")
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val response = stream?.bufferedReader()?.readText() ?: "{}"
            if (code !in 200..299) throw RuntimeException("HTTP $code: $response")
            JSONObject(response)
        } finally {
            conn.disconnect()
        }
    }

    // ── 새로 추가된 QR 세션 폴링 API ─────────────────────────────────────────────

    data class SessionStatusResult(
        val isComplete: Boolean,
        val accessToken: String = "",
        val refreshToken: String = "",
        val plateNumber: String = ""
    )

    /**
     * FastAPI 백엔드에 현재 QR 세션(loginSessionId)의 인증이 완료되었는지 확인합니다.
     */
    fun checkLoginSession(sessionId: String): SessionStatusResult {
        try {
            val response = getJson(URL("$BASE_URL/auth/session/$sessionId/status"))
            val status = response.optString("status", "pending")

            return if (status == "complete") {
                SessionStatusResult(
                    isComplete = true,
                    accessToken = response.getString("access_token"),
                    refreshToken = response.getString("refresh_token"),
                    plateNumber = response.getString("plate_number")
                )
            } else {
                SessionStatusResult(isComplete = false)
            }
        } catch (e: Exception) {
            // 서버 연결 실패 시 pending 상태로 리턴하여 계속 폴링하도록 유도
            Log.e(TAG, "checkLoginSession 실패: ${e.message}")
            return SessionStatusResult(isComplete = false)
        }
    }
}