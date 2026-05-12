package com.example.carpayin.network

import android.util.Log
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object ApiManager {
    private const val TAG = "ApiManager"

    // 에뮬레이터 → 호스트 PC 통신용 (앱 내부 API 호출)
    const val BASE_URL = "http://10.0.2.2:8000"

    // QR 코드용 — ngrok 공개 URL (ngrok http 8000 실행 후 발급된 주소로 교체)
    // 예: "https://abc123.ngrok-free.app"
    // ngrok 없이 같은 와이파이 환경이면: "http://192.168.201.213:8000"
    const val QR_BASE_URL = "https://pretext-armless-wieldable.ngrok-free.dev"

    // ── 데이터 클래스 ─────────────────────────────────────────────────────────

    data class VinInfo(
        val vin: String,
        val carId: String,
        val modelName: String,
        val year: Int
    )

    data class CardOrderResult(val orderId: String, val pgUrl: String)
    data class ParkingLotInfo(val id: String, val name: String, val lat: Double, val lng: Double)
    data class FeeResult(val lotName: String, val durationMinutes: Int, val amount: Int)
    data class PaymentResult(val transactionId: String, val approvalNumber: String)
    data class TokenResult(val accessToken: String, val refreshToken: String)

    // ── 마이현대 OAuth 세션 폴링 ──────────────────────────────────────────────

    data class SessionStatusResult(
        val isComplete: Boolean,
        val accessToken: String = "",
        val refreshToken: String = "",
        val plateNumber: String = "",
        val userId: String = "",
        val userName: String = "",
        val modelName: String = "",
        val vinList: List<VinInfo> = emptyList(),
        val cardLastFour: String = "****",
        val cardBrand: String = "현대카드"
    )

    fun checkLoginSession(sessionId: String): SessionStatusResult {
        return try {
            val response = getJson(URL("$BASE_URL/auth/session/$sessionId/status"))
            val status = response.optString("status", "pending")

            if (status == "complete") {
                val vinArray = response.optJSONArray("vin_list")
                val vinList = if (vinArray != null) {
                    (0 until vinArray.length()).map { i ->
                        val item = vinArray.getJSONObject(i)
                        VinInfo(
                            vin       = item.getString("vin"),
                            carId     = item.getString("car_id"),
                            modelName = item.optString("model_name", ""),
                            year      = item.optInt("year", 0)
                        )
                    }
                } else emptyList()

                SessionStatusResult(
                    isComplete   = true,
                    accessToken  = response.getString("access_token"),
                    refreshToken = response.getString("refresh_token"),
                    plateNumber  = response.getString("plate_number"),
                    userId       = response.optString("user_id", ""),
                    userName     = response.optString("user_name", ""),
                    modelName    = response.optString("model_name", ""),
                    vinList      = vinList,
                    cardLastFour = response.optString("card_last_four", "****"),
                    cardBrand    = response.optString("card_brand", "현대카드")
                )
            } else {
                SessionStatusResult(isComplete = false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "checkLoginSession 실패: ${e.message}")
            SessionStatusResult(isComplete = false)
        }
    }

    // ── VIN 확정 ──────────────────────────────────────────────────────────────

    fun confirmVin(vin: String, carId: String, accessToken: String) {
        val body = JSONObject().apply {
            put("vin", vin)
            put("car_id", carId)
        }.toString()
        postJson(URL("$BASE_URL/auth/confirm-vin"), body, accessToken)
    }

    // ── 토큰 갱신 ─────────────────────────────────────────────────────────────

    fun refreshToken(refreshToken: String): TokenResult {
        val body = JSONObject().apply { put("refresh_token", refreshToken) }.toString()
        val response = postJson(URL("$BASE_URL/auth/refresh"), body)
        return TokenResult(
            accessToken  = response.getString("access_token"),
            refreshToken = response.getString("refresh_token")
        )
    }

    // ── 카드 등록 주문 ────────────────────────────────────────────────────────

    /**
     * 백엔드에서 order_id를 발급받고 Mock PG WebView URL을 반환.
     * VIN은 URL에 싣지 않고 Authorization 헤더 토큰으로만 전달.
     * 서버가 토큰 → VIN 내부 조회 (VIN 외부 노출 차단).
     */
    fun fetchCardOrder(accessToken: String): CardOrderResult {
        val response = getJson(URL("$BASE_URL/card/order"), accessToken)
        return CardOrderResult(
            orderId = response.getString("order_id"),
            pgUrl   = response.getString("pg_url")
        )
    }

    // ── 주차장 목록 조회 ──────────────────────────────────────────────────────

    fun fetchParkingLots(): List<ParkingLotInfo> {
        return try {
            val response = getJson(URL("$BASE_URL/parking/lots"))
            val arr = response.optJSONArray("lots") ?: return emptyList()
            (0 until arr.length()).map { i ->
                val item = arr.getJSONObject(i)
                ParkingLotInfo(
                    id   = item.getString("id"),
                    name = item.getString("name"),
                    lat  = item.getDouble("lat"),
                    lng  = item.getDouble("lng")
                )
            }
        } catch (e: Exception) {
            listOf(
                ParkingLotInfo("LOT_GN_01", "강남 CarPayIn 주차장", 37.4979, 127.0276),
                ParkingLotInfo("LOT_HD_01", "홍대 CarPayIn 주차장", 37.5567, 126.9236)
            )
        }
    }

    // ── 사전 알림 ─────────────────────────────────────────────────────────────

    fun sendPreNotification(
        vin: String, plate: String, lotId: String,
        triggerType: String, accessToken: String
    ) {
        val body = JSONObject().apply {
            put("vin", vin)
            put("plate", plate)
            put("lot_id", lotId)
            put("trigger", triggerType.lowercase())
        }.toString()
        postJson(URL("$BASE_URL/pre-notify"), body, accessToken)
    }

    // ── 요금 조회 ─────────────────────────────────────────────────────────────

    fun queryFee(lotId: String, sessionId: String, accessToken: String): FeeResult {
        val response = getJson(URL("$BASE_URL/fee/$sessionId"), accessToken)
        return FeeResult(
            lotName         = response.optString("lot_name", lotId),
            durationMinutes = response.optInt("duration_minutes", 0),
            amount          = response.getInt("amount")
        )
    }

    // ── 결제 요청 ─────────────────────────────────────────────────────────────

    fun requestPayment(sessionId: String, amount: Int, accessToken: String): PaymentResult {
        val body = JSONObject().apply {
            put("session_id", sessionId)
            put("amount", amount)
        }.toString()
        val response = postJson(URL("$BASE_URL/payment"), body, accessToken)
        return PaymentResult(
            transactionId  = response.getString("tx_id"),
            approvalNumber = response.getString("approval_no")
        )
    }

    // ── 내부 HTTP 유틸 ────────────────────────────────────────────────────────

    private fun postJson(url: URL, body: String, accessToken: String? = null): JSONObject {
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            if (accessToken != null) conn.setRequestProperty("Authorization", "Bearer $accessToken")
            conn.doOutput       = true
            conn.connectTimeout = 10_000
            conn.readTimeout    = 30_000
            OutputStreamWriter(conn.outputStream).use { it.write(body) }
            val code   = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text   = stream?.bufferedReader()?.readText() ?: "{}"
            if (code !in 200..299) throw RuntimeException("HTTP $code: $text")
            JSONObject(text)
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
            conn.readTimeout    = 30_000
            val code   = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text   = stream?.bufferedReader()?.readText() ?: "{}"
            if (code !in 200..299) throw RuntimeException("HTTP $code: $text")
            JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}
