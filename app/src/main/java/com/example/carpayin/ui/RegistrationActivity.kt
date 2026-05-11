package com.example.carpayin.ui

import com.example.carpayin.R
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.example.carpayin.data.ParkingStateManager
import com.example.carpayin.network.ApiManager
import com.example.carpayin.vehicle.VehicleDataManager
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.util.UUID

/**
 * 마이현대 OAuth QR 연동 화면
 *
 * 흐름:
 *  1. AAOS 앱이 고유 session_id를 생성하고 QR 코드를 화면에 표시
 *  2. 사용자가 스마트폰 카메라로 QR 스캔
 *  3. 스마트폰 브라우저 → 백엔드 /auth/hyundai/start → 현대 OAuth 페이지로 리디렉션
 *  4. 사용자가 마이현대 계정으로 로그인
 *  5. 현대 OAuth → 백엔드 콜백 → 세션에 토큰·차량정보 저장
 *  6. AAOS 앱(폴링) → 완료 감지 → 토큰·사용자 정보 저장 후 MainActivity로 복귀
 *
 * 백엔드 QR URL:
 *   http(s)://[backend]/auth/hyundai/start?session_id={uuid}&vin={vin}
 *
 * 현대 개발자 포털 OAuth:
 *   인증 엔드포인트 — https://accounts.hyundai.com/auth/oauth/v2/authorize
 *   Client ID / Secret 은 백엔드에서 관리 (앱은 session_id만 보유)
 */
class RegistrationActivity : Activity() {

    private val TAG = "RegistrationActivity"
    private val handler = Handler(Looper.getMainLooper())
    private var isPolling = false

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var ivQrCode: ImageView
    private lateinit var tvPollingStatus: TextView
    private lateinit var tvSubMessage: TextView
    private lateinit var btnCancel: Button
    private lateinit var btnRefreshQr: Button

    // ── 세션 데이터 ───────────────────────────────────────────────────────────
    private lateinit var loginSessionId: String
    private lateinit var vin: String

    // ── 폴링 타임아웃 (5분) ───────────────────────────────────────────────────
    private val POLL_TIMEOUT_MS = 5 * 60 * 1000L
    private var pollStartTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_registration)

        ivQrCode        = findViewById(R.id.ivQrCode)
        tvPollingStatus = findViewById(R.id.tvPollingStatus)
        tvSubMessage    = findViewById(R.id.tvSubMessage)
        btnCancel       = findViewById(R.id.btnCancel)
        btnRefreshQr    = findViewById(R.id.btnRefreshQr)

        vin            = VehicleDataManager.readVin(this)
        loginSessionId = UUID.randomUUID().toString()

        btnCancel.setOnClickListener {
            isPolling = false
            setResult(RESULT_CANCELED)
            finish()
        }

        btnRefreshQr.setOnClickListener {
            // 기존 폴링 중단 후 새 session_id로 QR 재생성
            isPolling      = false
            handler.removeCallbacksAndMessages(null)
            loginSessionId = UUID.randomUUID().toString()
            ivQrCode.setImageBitmap(null)
            tvPollingStatus.text = "스마트폰으로 QR을 스캔해 주세요"
            tvSubMessage.text    = "마이현대 계정으로 로그인하면\n차량이 자동으로 연동됩니다"
            renderQrCode()
            startPolling()
        }

        renderQrCode()
        startPolling()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // QR 코드 생성 및 표시
    // ─────────────────────────────────────────────────────────────────────────

    private fun renderQrCode() {
        // QR 코드는 실제 폰이 스캔하므로 PC의 로컬 IP(QR_BASE_URL)를 사용합니다.
        // 에뮬레이터 내부 API 호출(BASE_URL)과 다른 주소입니다.
        val authStartUrl =
            "${ApiManager.QR_BASE_URL}/auth/hyundai/start?session_id=$loginSessionId&vin=$vin"

        Log.d(TAG, "QR URL: $authStartUrl")

        tvPollingStatus.text = "스마트폰으로 QR을 스캔해 주세요"
        tvSubMessage.text    = "마이현대 계정으로 로그인하면\n차량이 자동으로 연동됩니다"

        Thread {
            try {
                val bits   = QRCodeWriter().encode(authStartUrl, BarcodeFormat.QR_CODE, 512, 512)
                val w      = bits.width
                val h      = bits.height
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
                for (x in 0 until w) {
                    for (y in 0 until h) {
                        bitmap.setPixel(x, y, if (bits[x, y]) Color.BLACK else Color.WHITE)
                    }
                }
                handler.post { ivQrCode.setImageBitmap(bitmap) }
            } catch (e: Exception) {
                Log.e(TAG, "QR 생성 실패: ${e.message}")
                handler.post {
                    tvPollingStatus.text = "QR 생성 실패"
                    tvSubMessage.text    = "앱을 재시작해 주세요"
                }
            }
        }.start()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 세션 폴링 (2초 간격, 5분 타임아웃)
    // ─────────────────────────────────────────────────────────────────────────

    private fun startPolling() {
        isPolling     = true
        pollStartTime = System.currentTimeMillis()
        scheduleNextPoll()
    }

    private fun scheduleNextPoll() {
        handler.postDelayed({ doPoll() }, 2_000)
    }

    private fun doPoll() {
        if (!isPolling) return

        // 타임아웃 체크
        if (System.currentTimeMillis() - pollStartTime > POLL_TIMEOUT_MS) {
            isPolling = false
            tvPollingStatus.text = "시간이 초과되었습니다"
            tvSubMessage.text    = "다시 시도하려면 취소 후 재진입해 주세요"
            return
        }

        Thread {
            try {
                val result = ApiManager.checkLoginSession(loginSessionId)
                if (result.isComplete) {
                    handler.post { onLoginComplete(result) }
                } else {
                    handler.post { scheduleNextPoll() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "폴링 오류: ${e.message}")
                handler.post { scheduleNextPoll() }
            }
        }.start()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 로그인 완료 처리
    // ─────────────────────────────────────────────────────────────────────────

    private fun onLoginComplete(result: ApiManager.SessionStatusResult) {
        isPolling = false
        tvPollingStatus.text = "연동 완료!"
        tvSubMessage.text    = "잠시 후 이동합니다..."

        // ── VIN 매칭: VHAL VIN과 마이현대 차량 목록 비교 ─────────────────────
        val matchedVin = result.vinList.firstOrNull { it.vin == vin }
            ?: result.vinList.firstOrNull()   // 일치하는 VIN 없으면 첫 번째 차량 선택

        val selectedVin    = matchedVin?.vin    ?: vin
        val selectedCarId  = matchedVin?.carId  ?: ""
        val selectedModel  = matchedVin?.modelName ?: result.modelName

        // ── 데이터 저장 ───────────────────────────────────────────────────────
        ParkingStateManager.saveTokens(
            this,
            result.accessToken,
            result.refreshToken
        )
        ParkingStateManager.savePlateNumber(this, result.plateNumber)
        ParkingStateManager.saveHyundaiUserInfo(
            this,
            result.userId,
            result.userName,
            selectedModel
        )
        ParkingStateManager.saveCardInfo(this, result.cardLastFour, result.cardBrand)
        ParkingStateManager.setRegistered(this, true)

        // ── 백엔드에 최종 VIN 확정 알림 ──────────────────────────────────────
        if (selectedCarId.isNotEmpty()) {
            Thread {
                runCatching {
                    ApiManager.confirmVin(selectedVin, selectedCarId, result.accessToken)
                    Log.d(TAG, "VIN 확정 전송 완료: $selectedVin")
                }.onFailure {
                    Log.w(TAG, "VIN 확정 전송 실패 (무시): ${it.message}")
                }
            }.start()
        }

        val displayName = result.userName.ifEmpty { "차량" }
        Toast.makeText(this, "$displayName 님, 연동 완료!", Toast.LENGTH_SHORT).show()

        handler.postDelayed({
            setResult(RESULT_OK)
            finish()
        }, 1_000)
    }

    override fun onDestroy() {
        super.onDestroy()
        isPolling = false
        handler.removeCallbacksAndMessages(null)
    }
}
