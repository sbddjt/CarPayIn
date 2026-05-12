package com.example.carpayin.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * EncryptedSharedPreferences 기반 보안 상태 저장소
 *
 * 저장 항목:
 *  - access_token / refresh_token   (차량 인증 후 발급)
 *  - payment_method_id              (카드 등록 완료 후 저장)
 *  - plate_number                   (국토부 Mock API 조회 결과)
 *  - registered                     (최초 등록 완료 여부)
 *  - parked / lot_id / session_id   (입차 확정 알림 수신 후 저장)
 */
object ParkingStateManager {
    private const val TAG = "ParkingStateManager"
    private const val PREF_FILE = "carpayin_secure"

    private fun getPrefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREF_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // 에뮬레이터 등 환경에서 EncryptedSharedPreferences 실패 시 일반 prefs로 fallback
            Log.w(TAG, "EncryptedSharedPreferences 초기화 실패, fallback 사용: ${e.message}")
            context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        }
    }

    // ── 토큰 (액세스 토큰: 1시간, 리프레시 토큰: 30일) ──────────────────────

    private const val TOKEN_LIFETIME_MS   = 60 * 60 * 1000L             // 1시간
    private const val REFRESH_LIFETIME_MS = 30 * 24 * 60 * 60 * 1000L  // 30일

    fun saveTokens(context: Context, accessToken: String, refreshToken: String) {
        val now = System.currentTimeMillis()
        getPrefs(context).edit()
            .putString("access_token", accessToken)
            .putString("refresh_token", refreshToken)
            .putLong("token_expiry", now + TOKEN_LIFETIME_MS)
            .putLong("refresh_expiry", now + REFRESH_LIFETIME_MS)
            .apply()
        Log.d(TAG, "토큰 저장 완료 (만료: ${java.util.Date(now + TOKEN_LIFETIME_MS)})")
    }

    fun getAccessToken(context: Context): String? =
        getPrefs(context).getString("access_token", null)

    fun getRefreshToken(context: Context): String? =
        getPrefs(context).getString("refresh_token", null)

    /** 액세스 토큰 만료 timestamp (ms). 0이면 저장된 정보 없음 */
    fun getTokenExpiry(context: Context): Long =
        getPrefs(context).getLong("token_expiry", 0L)

    /** 리프레시 토큰 만료 여부 확인 */
    fun isRefreshTokenExpired(context: Context): Boolean {
        val expiry = getPrefs(context).getLong("refresh_expiry", 0L)
        return expiry > 0 && System.currentTimeMillis() > expiry
    }

    // ── 결제 수단 ID (카드 원번호는 앱에 없음, ID만 보관) ────────────────────
    fun savePaymentMethodId(context: Context, id: String) {
        getPrefs(context).edit().putString("payment_method_id", id).apply()
        Log.d(TAG, "payment_method_id 저장 완료")
    }

    fun getPaymentMethodId(context: Context): String? =
        getPrefs(context).getString("payment_method_id", null)

    fun hasPaymentMethod(context: Context): Boolean =
        getPaymentMethodId(context) != null

    // ── 카드 표시 정보 (마지막 4자리 + 카드사) ──────────────────────────────
    fun saveCardInfo(context: Context, lastFour: String, cardBrand: String) {
        getPrefs(context).edit()
            .putString("card_last_four", lastFour)
            .putString("card_brand", cardBrand)
            .apply()
    }

    fun getCardLastFour(context: Context): String =
        getPrefs(context).getString("card_last_four", "****") ?: "****"

    fun getCardBrand(context: Context): String =
        getPrefs(context).getString("card_brand", "카드") ?: "카드"

    // ── 번호판 (VIN → 국토부 Mock API → 확인 후 저장) ───────────────────────
    fun savePlateNumber(context: Context, plate: String) {
        getPrefs(context).edit().putString("plate_number", plate).apply()
        Log.d(TAG, "번호판 저장: $plate")
    }

    fun getPlateNumber(context: Context): String? =
        getPrefs(context).getString("plate_number", null)

    // ── 등록 완료 여부 ────────────────────────────────────────────────────────
    fun isRegistered(context: Context): Boolean =
        getPrefs(context).getBoolean("registered", false)

    fun setRegistered(context: Context, registered: Boolean) {
        getPrefs(context).edit().putBoolean("registered", registered).apply()
    }

    // ── OAuth(마이현대 로그인) 완료 여부 ─────────────────────────────────────
    // 카드 등록 전에 앱을 껐다가 다시 켰을 때, QR 화면 건너뛰고 카드 등록만 뜨도록 사용
    fun isOAuthComplete(context: Context): Boolean =
        getPrefs(context).getBoolean("oauth_complete", false)

    fun setOAuthComplete(context: Context, complete: Boolean) {
        getPrefs(context).edit().putBoolean("oauth_complete", complete).apply()
    }

    // ── 주차 상태 (입차 확정 알림 수신 시 저장) ──────────────────────────────
    /**
     * @param parked    주차 중 여부
     * @param lotId     주차장 ID (입차 시 저장, 출차 시 빈 문자열)
     * @param sessionId 파킹 세션 ID (백엔드 Kafka Consumer 처리 완료 후 발급)
     */
    fun saveParkingState(
        context: Context,
        parked: Boolean,
        lotId: String = "",
        sessionId: String = ""
    ) {
        getPrefs(context).edit()
            .putBoolean("parked", parked)
            .putString("lot_id", if (parked) lotId else "")
            .putString("session_id", if (parked) sessionId else "")
            .apply()
        Log.d(TAG, "주차 상태 저장: parked=$parked, lot=$lotId")
    }

    fun isParked(context: Context): Boolean =
        getPrefs(context).getBoolean("parked", false)

    fun getLotId(context: Context): String =
        getPrefs(context).getString("lot_id", "") ?: ""

    fun getSessionId(context: Context): String =
        getPrefs(context).getString("session_id", "") ?: ""

    // ── 마이현대 사용자 정보 ──────────────────────────────────────────────────
    fun saveHyundaiUserInfo(
        context: Context,
        userId: String,
        userName: String,
        modelName: String
    ) {
        getPrefs(context).edit()
            .putString("hyundai_user_id", userId)
            .putString("hyundai_user_name", userName)
            .putString("hyundai_model_name", modelName)
            .apply()
        Log.d(TAG, "마이현대 사용자 저장: $userName / $modelName")
    }

    fun getHyundaiUserId(context: Context): String =
        getPrefs(context).getString("hyundai_user_id", "") ?: ""

    fun getHyundaiUserName(context: Context): String =
        getPrefs(context).getString("hyundai_user_name", "") ?: ""

    fun getHyundaiModelName(context: Context): String =
        getPrefs(context).getString("hyundai_model_name", "") ?: ""
}
