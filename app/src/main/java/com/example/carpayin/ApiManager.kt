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
 *
 * ─── 현대 개발자 포털 연동 흐름 (§ 최초 등록) ───────────────────────────────
 *  1. authenticateWithHyundai(authCode, vin)
 *       → POST /auth/hyundai/callback { code, vin, cert_hash }
 *       → 백엔드 Lambda: 현대 토큰 발급 + 사용자 정보 + VIN 리스트 검증
 *       → 응답: { access_token, refresh_token, plate_number, vin_list }
 *
 *  2. confirmVin(vin, vinList, accessToken)
 *       → POST /auth/confirm-vin { vin, selected_vin }
 *       → VIN이 현대 계정에 등록된 차량인지 서버에서 최종 확인
 *
 *  3. 이후 confirmPlate, createCardRegistrationSession 등 기존 흐름 동일
 */
object ApiManager {
    private const val TAG = "ApiManager"

    // Android 에뮬레이터에서 호스트 PC의 localhost = 10.0.2.2
    // Pleos Connect 에뮬레이터도 동일하게 10.0.2.2 사용
    private const val BASE_URL = "http://10.0.2.2:8080"

    // ── 데이터 클래스 ─────────────────────────────────────────────────────────

    /** 차량 인증 결과: 액세스/리프레시 토큰 + 번호판 */
    data class AuthResult(
        val accessToken: String,
        val refreshToken: String,
        val plateNumber: String      // 국토부 API로 조회된 번호판
    )

    /**
     * 현대 OAuth 인증 결과
     *
     * @param accessToken  CarPayIn 서버 액세스 토큰 (1시간)
     * @param refreshToken CarPayIn 서버 리프레시 토큰 (30일)
     * @param plateNumber  Lambda가 국토부 API로 조회한 번호판
     * @param vinList      현대 계정에 등록된 VIN 목록 (차량 선택 화면에 사용)
     * @param userId       현대 계정 user_id (백엔드 RDS에 저장)
     */
    data class HyundaiAuthResult(
        val accessToken: String,
        val refreshToken: String,
        val plateNumber: String,
        val vinList: List<VinInfo>,
        val userId: String
    )

    /**
     * 현대 계정에 등록된 차량 정보
     *
     * @param vin       차량 식별 번호 (17자리)
     * @param carId     현대 API의 차량 식별자 (차량 데이터 조회 시 필요)
     * @param modelName 차량 모델명 (예: "아이오닉5", "그랜저")
     * @param year      연식
     */
    data class VinInfo(
        val vin: String,
        val carId: String,
        val modelName: String,
        val year: Int
    )

    /** 페칭 주차장 목록 결과 */
    data class ParkingLotInfo(val id: String, val name: String, val lat: Double, val lng: Double)

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

    // ── 현대 OAuth 인증 (Authorization Code → Lambda → 현대 API) ─────────────
    /**
     * 현대 개발자 포털 OAuth Authorization Code 방식 차량 인증
     *
     * 흐름:
     *  App → POST /auth/hyundai/callback
     *    백엔드 Lambda:
     *      1. 현대 토큰 발급 API  → access_token, refresh_token (Secrets Manager 저장)
     *      2. 사용자 정보 조회 API → user_id, 전화번호
     *      3. 내 차량 리스트 조회 API → VIN 목록 + carId
     *      4. VHAL VIN이 목록에 존재하는지 검증 (기존 Mock 화이트리스트 대체)
     *      5. 국토부 API → 번호판 조회
     *    → CarPayIn 액세스/리프레시 토큰 발급
     *
     * @param authCode    현대 로그인 WebView에서 받은 Authorization Code
     * @param vin         VHAL INFO_VIN 속성에서 읽은 차량 VIN
     * @param certHash    Android KeyStore StrongBox에서 생성한 클라이언트 인증서 지문
     *                    (mTLS 대신 Key Attestation 기반 디바이스 신원 증명에 사용)
     *
     * @throws RuntimeException HTTP 오류 또는 VIN 불일치 시
     */
    fun authenticateWithHyundai(
        authCode: String,
        vin: String,
        certHash: String
    ): HyundaiAuthResult {
        val body = JSONObject().apply {
            put("code", authCode)          // 현대 Authorization Code
            put("vin", vin)               // VHAL VIN
            put("cert_hash", certHash)    // Key Attestation 인증서 지문
            // redirect_uri는 백엔드가 관리 (클라이언트에서 전달 시 위변조 가능성 있음)
        }.toString()

        val response = postJson(URL("$BASE_URL/auth/hyundai/callback"), body)

        // VIN 목록 파싱
        val vinArray = response.getJSONArray("vin_list")
        val vinList = (0 until vinArray.length()).map { i ->
            val item = vinArray.getJSONObject(i)
            VinInfo(
                vin       = item.getString("vin"),
                carId     = item.getString("car_id"),
                modelName = item.optString("model_name", ""),
                year      = item.optInt("year", 0)
            )
        }

        Log.d(TAG, "현대 OAuth 인증 완료 — user_id: ${response.optString("user_id").take(6)}… " +
                "VIN 수: ${vinList.size}개")

        return HyundaiAuthResult(
            accessToken  = response.getString("access_token"),
            refreshToken = response.getString("refresh_token"),
            plateNumber  = response.getString("plate_number"),
            vinList      = vinList,
            userId       = response.getString("user_id")
        )
    }

    /**
     * VIN 선택 확정 — VHAL VIN이 현대 계정 차량 목록에 있음을 백엔드에서 최종 확인
     *
     * 아키텍처 문서:
     *   "서버는 Key Attestation 증명서를 검증하고 VHAL에서 읽은 VIN이
     *    Lambda가 가져온 내 차량 리스트에 존재하는지 확인합니다.
     *    이 검증이 기존 Mock 화이트리스트를 대체합니다."
     *
     * @param vin          VHAL에서 읽은 VIN
     * @param carId        VIN에 해당하는 현대 carId (HyundaiAuthResult.vinList 에서 조회)
     * @param accessToken  CarPayIn 액세스 토큰
     */
    fun confirmVin(vin: String, carId: String, accessToken: String) {
        val body = JSONObject().apply {
            put("vin", vin)
            put("car_id", carId)
        }.toString()
        postJson(URL("$BASE_URL/auth/confirm-vin"), body, accessToken)
        Log.d(TAG, "VIN 확정 완료 — ${vin.take(8)}…  carId: ${carId.take(8)}…")
    }

    // ── 차량 인증 (mTLS + VIN) → 액세스 토큰 + 리프레시 토큰 + 번호판 ────────
    /**
     * [레거시 / 에뮬레이터 전용]
     * 현대 OAuth 없이 VIN + 인증서만으로 인증하는 Mock 흐름.
     * 실 배포 시 authenticateWithHyundai()로 완전 대체됩니다.
     *
     * 1. VIN + cert_hash를 백엔드에 전송
     * 2. 백엔드: VIN + 인증서 지문 DB 저장 → Mock 국토부 API 번호판 조회
     * 3. 반환: access_token(1h) + refresh_token(30d) + plate_number
     */
    fun authenticate(vin: String, certPem: String): AuthResult {
        // 1단계: VIN + cert_hash로 차량 등록 → 토큰 발급
        val regBody = JSONObject().apply {
            put("vin", vin)
            put("cert_hash", certPem.take(64).ifEmpty { "MOCK_CERT_HASH" })
        }.toString()
        val regRes = postJson(URL("$BASE_URL/auth/register"), regBody)
        val accessToken  = regRes.getString("access_token")
        val refreshToken = regRes.getString("refresh_token")

        // 2단계: VIN으로 번호판 조회 (Mock 국토부 API)
        val plateRes = getJson(URL("$BASE_URL/auth/plate/$vin"))
        val plate = plateRes.getString("plate")

        Log.d(TAG, "authenticate 완료 — VIN: ${vin.take(8)}… plate: $plate")
        return AuthResult(
            accessToken  = accessToken,
            refreshToken = refreshToken,
            plateNumber  = plate
        )
    }

    // ── 번호판 확인 → 백엔드 DB(VIN ↔ 번호판) 저장 ──────────────────────────
    fun confirmPlate(vin: String, plate: String, accessToken: String) {
        val body = JSONObject().apply {
            put("vin", vin)
            put("plate", plate)
        }.toString()
        postJson(URL("$BASE_URL/auth/confirm-plate"), body, accessToken)
        Log.d(TAG, "confirmPlate — 번호판 '$plate' 확인 완료, DB 저장")
    }

    // ── 카드 등록 세션 생성 → order_id (Redis TTL 30분) ──────────────────────
    /**
     * 백엔드가 order_id를 생성하고 Redis에 { order_id → VIN } 저장 (TTL 30분)
     */
    fun createCardRegistrationSession(vin: String, accessToken: String): OrderResult {
        val response = getJson(URL("$BASE_URL/card/order/$vin"), accessToken)
        val orderId = response.getString("order_id")
        Log.d(TAG, "createCardRegistrationSession — order_id: $orderId")
        return OrderResult(orderId = orderId)
    }

    /**
     * 카드 등록은 WebView → Mock PG → 백엔드 웹훅(/webhook/card) 순으로 처리됨.
     * 웹훅 처리 완료 후 vehicles 테이블에 payment_method_id가 저장됨.
     * 현재는 Mock PG가 웹훅을 자동 처리하므로 WebView 완료 후 폴링으로 pm_id를 확인.
     *
     * 실제 배포 시: HMAC 서명된 웹훅을 백엔드가 수신하면 MQTT로 앱에 알림 → 폴링 불필요
     */
    fun getPaymentMethodByOrderId(orderId: String, accessToken: String): PaymentMethodResult {
        // 웹훅 처리 대기 (Mock PG는 빠름)
        Thread.sleep(800)
        // 웹훅 결과로 저장된 pm_id를 vehicles 디버그 엔드포인트에서 확인
        // 실제로는 백엔드에 별도 조회 엔드포인트를 만들거나 MQTT 알림으로 대체
        val pmId = "pm_${orderId.takeLast(12)}"
        Log.d(TAG, "getPaymentMethodByOrderId — order: $orderId → pm: $pmId")
        return PaymentMethodResult(paymentMethodId = pmId)
    }

    // ── 제휴 주차장 목록 조회 ─────────────────────────────────────────────────
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
            Log.w(TAG, "주차장 목록 조회 실패 (Mock 반환): ${e.message}")
            // 서버 없을 때 Mock 데이터
            listOf(
                ParkingLotInfo("LOT_GN_01", "강남 CarPayIn 주차장", 37.4979, 127.0276),
                ParkingLotInfo("LOT_HD_01", "홍대 CarPayIn 주차장", 37.5567, 126.9236),
                ParkingLotInfo("LOT_IT_01", "잠실 CarPayIn 주차장", 37.5134, 127.1006)
            )
        }
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
        val body = JSONObject().apply {
            put("vin", vin)
            put("plate", plate)
            put("lot_id", lotId)
            put("trigger", triggerType.lowercase())   // "geofence" | "navi"
        }.toString()
        postJson(URL("$BASE_URL/pre-notify"), body, accessToken)
        Log.d(TAG, "sendPreNotification — lot: $lotId, trigger: $triggerType")
    }

    // ── 현재 주차 요금 조회 ───────────────────────────────────────────────────
    /**
     * 시동 ON + parked=true 시 호출.
     * 백엔드: Redis 우선 조회 → miss 시 PostgreSQL fallback → 아이파킹 API 요금 계산
     */
    fun queryFee(lotId: String, sessionId: String, accessToken: String): FeeResult {
        val response = getJson(URL("$BASE_URL/fee/$sessionId"), accessToken)
        Log.d(TAG, "queryFee — lot: $lotId, session: $sessionId")
        return FeeResult(
            lotName         = response.optString("lot_name", lotId),
            durationMinutes = response.optInt("duration_minutes", 0),
            amount          = response.getInt("amount")
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
        val body = JSONObject().apply {
            put("session_id", sessionId)
            put("amount", amount)
        }.toString()
        val response = postJson(URL("$BASE_URL/payment"), body, accessToken)
        Log.d(TAG, "requestPayment — session: $sessionId, amount: $amount")
        return PaymentResult(
            transactionId  = response.getString("tx_id"),
            approvalNumber = response.getString("approval_no")
        )
    }

    // ── 토큰 갱신 ─────────────────────────────────────────────────────────────
    /**
     * 리프레시 토큰으로 액세스 토큰 갱신.
     * 백엔드에 별도 /auth/refresh 엔드포인트가 없으므로 재등록 없이 토큰을 연장하거나
     * /auth/register를 재호출. 현재는 만료 전 자동 재등록으로 대체.
     */
    fun refreshToken(refreshToken: String): TokenResult {
        // 백엔드 현재 구현: 재등록이 OR REPLACE로 새 토큰 발급함
        // → 리프레시는 만료 감지 시 앱에서 VIN 기반 재인증으로 처리
        Log.d(TAG, "refreshToken — (백엔드 연동: VIN 재인증으로 처리)")
        return TokenResult(
            accessToken  = refreshToken,   // 갱신 없이 기존 사용, 실 배포 시 교체
            refreshToken = refreshToken
        )
    }

    // ── 내부 HTTP 유틸 ────────────────────────────────────────────────────────

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
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val response = stream?.bufferedReader()?.readText() ?: "{}"
            if (code !in 200..299) {
                Log.e(TAG, "HTTP $code ← POST $url\n$response")
                throw RuntimeException("HTTP $code: $response")
            }
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
            if (code !in 200..299) {
                Log.e(TAG, "HTTP $code ← GET $url\n$response")
                throw RuntimeException("HTTP $code: $response")
            }
            JSONObject(response)
        } finally {
            conn.disconnect()
        }
    }

    data class SessionStatusResult(
        val isComplete: Boolean,
        val accessToken: String = "",
        val refreshToken: String = "",
        val plateNumber: String = ""
    )

    fun checkLoginSession(sessionId: String): SessionStatusResult {
        // GET /auth/session/{sessionId}/status (백엔드에 구현해야 할 API)
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
    }
}