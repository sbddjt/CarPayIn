package com.example.carpayin

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.WebView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/**
 * 최초 등록 Activity — 마이현대 OAuth 로그인 한 번으로 등록 완료
 *
 * 흐름:
 * 1. 마이현대 OAuth WebView 로그인
 * 2. Auth Code → CarPayIn 백엔드 → 현대 API → carId + 번호판 자동 수신
 * 3. "이 차량이 맞습니까?" 확인 다이얼로그
 * 4. 확인 탭 → 등록 완료 (카드 입력 없음, billing_key는 백엔드가 현대 페이에서 관리)
 */
class RegistrationActivity : Activity() {

    private val TAG = "RegistrationActivity"
    private val handler = Handler(Looper.getMainLooper())

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var layoutRegLoading: LinearLayout
    private lateinit var tvRegStep: TextView
    private lateinit var tvRegDetail: TextView
    private lateinit var progressBarReg: ProgressBar
    private lateinit var btnRegRetry: Button
    private lateinit var layoutWebViewHeader: LinearLayout
    private lateinit var btnWebViewCancel: Button
    private lateinit var webView: WebView

    // ── 차량 데이터 ────────────────────────────────────────────────────────────
    private var vin: String = ""
    private var plate: String = ""

    // ─────────────────────────────────────────────────────────────────────────
    // onCreate
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_registration)

        layoutRegLoading    = findViewById(R.id.layoutRegLoading)
        tvRegStep           = findViewById(R.id.tvRegStep)
        tvRegDetail         = findViewById(R.id.tvRegDetail)
        progressBarReg      = findViewById(R.id.progressBarReg)
        btnRegRetry         = findViewById(R.id.btnRegRetry)
        layoutWebViewHeader = findViewById(R.id.layoutWebViewHeader)
        btnWebViewCancel    = findViewById(R.id.btnWebViewCancel)
        webView             = findViewById(R.id.webViewPg)

        // 카드 입력 ScrollView는 이 흐름에서 사용하지 않음 — 숨김 처리
        findViewById<View>(R.id.layoutRegCardInput).visibility = View.GONE

        btnWebViewCancel.setOnClickListener {
            webView.visibility             = View.GONE
            layoutWebViewHeader.visibility = View.GONE
            setResult(RESULT_CANCELED)
            finish()
        }

        startHyundaiLoginFlow()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 1: 마이현대 OAuth 로그인
    // ─────────────────────────────────────────────────────────────────────────

    private fun startHyundaiLoginFlow() {
        showLoadingState("마이현대 로그인", "현대 계정으로 로그인합니다")

        HyundaiOAuthManager.onAuthCodeReceived = { authCode ->
            handler.post {
                webView.visibility             = View.GONE
                layoutWebViewHeader.visibility = View.GONE
                showLoadingState("차량 정보 조회 중...", "마이현대 계정에서 차량을 불러옵니다")
            }
            continueWithAuthCode(authCode)
        }

        HyundaiOAuthManager.onAuthError = { reason ->
            handler.post { showError("로그인 실패", reason) }
        }

        handler.postDelayed({ showHyundaiLoginWebView() }, 400)
    }

    private fun showHyundaiLoginWebView() {
        handler.post {
            layoutRegLoading.visibility    = View.GONE
            webView.visibility             = View.VISIBLE
            layoutWebViewHeader.visibility = View.VISIBLE
            btnWebViewCancel.text          = "로그인 취소"
        }
        HyundaiOAuthManager.loadIntoWebView(webView, this)
        Log.d(TAG, "마이현대 로그인 WebView 표시")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 2: Auth Code → 백엔드 → carId + 번호판 수신
    // ─────────────────────────────────────────────────────────────────────────

    private fun continueWithAuthCode(authCode: String) {
        Thread {
            try {
                vin = VehicleDataManager.readVin(this)
                val certHash = KeystoreManager.generateKeyPairIfNeeded()
                    .take(64).ifEmpty { "MOCK_CERT_HASH" }

                // 백엔드: Hyundai API → carId + 번호판 + billing_key 저장
                val result = ApiManager.authenticateWithHyundai(
                    authCode = authCode,
                    vin      = vin,
                    certHash = certHash
                )

                ParkingStateManager.saveTokens(this, result.accessToken, result.refreshToken)
                plate = result.plateNumber

                // VHAL VIN과 현대 계정 차량 매칭
                val matched = result.vinList.find { it.vin == vin }

                handler.post {
                    if (matched == null && result.vinList.isNotEmpty()) {
                        showVehicleSelectDialog(result.vinList, result.accessToken)
                    } else {
                        val carId = matched?.carId ?: result.vinList.firstOrNull()?.carId ?: ""
                        val model = matched?.modelName ?: result.vinList.firstOrNull()?.modelName ?: "차량"
                        showVehicleConfirmDialog(vin, carId, model, plate, result.accessToken)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "인증 실패: ${e.message}")
                handler.post { showError("차량 조회 실패", "다시 시도해 주세요\n(${e.javaClass.simpleName})") }
            }
        }.start()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 3: 차량 확인 다이얼로그 → 등록 완료
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * "이 차량이 맞습니까?" — 사용자가 확인 버튼 하나만 누르면 등록 완료
     */
    private fun showVehicleConfirmDialog(
        vin: String, carId: String, modelName: String,
        plateNumber: String, accessToken: String
    ) {
        AlertDialog.Builder(this)
            .setTitle("차량을 확인해 주세요")
            .setMessage(
                "차종: $modelName\n" +
                "번호판: $plateNumber\n\n" +
                "이 차량으로 CarPayIn을 등록하시겠습니까?\n" +
                "결제 수단은 마이현대에 등록된 카드가 사용됩니다."
            )
            .setPositiveButton("확인, 등록합니다") { _, _ ->
                completeRegistration(vin, carId, plateNumber, accessToken)
            }
            .setNegativeButton("취소") { _, _ ->
                setResult(RESULT_CANCELED)
                finish()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * 차량 목록에서 선택 (VHAL VIN과 현대 계정 VIN이 다른 경우)
     */
    private fun showVehicleSelectDialog(
        vinList: List<ApiManager.VinInfo>,
        accessToken: String
    ) {
        val items = vinList.map { "${it.modelName}  ${it.vin.take(8)}…" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("차량을 선택해 주세요")
            .setItems(items) { _, idx ->
                val v = vinList[idx]
                showVehicleConfirmDialog(v.vin, v.carId, v.modelName, plate, accessToken)
            }
            .setCancelable(false)
            .show()
    }

    /**
     * 등록 완료 처리 — DB에 carId 확정 저장 후 바로 RESULT_OK
     */
    private fun completeRegistration(
        vin: String, carId: String, plateNumber: String, accessToken: String
    ) {
        showLoadingState("등록 중...", "차량 정보를 저장하고 있습니다")

        Thread {
            try {
                ApiManager.confirmVin(vin, carId, accessToken)
                ApiManager.confirmPlate(vin, plateNumber, accessToken)
                ParkingStateManager.savePlateNumber(this, plateNumber)
                ParkingStateManager.setRegistered(this, true)

                handler.post {
                    Toast.makeText(this, "✓ 등록이 완료되었습니다", Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK)
                    finish()
                }
            } catch (e: Exception) {
                Log.e(TAG, "등록 저장 실패: ${e.message}")
                handler.post { showError("등록 실패", "잠시 후 다시 시도해 주세요") }
            }
        }.start()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI 헬퍼
    // ─────────────────────────────────────────────────────────────────────────

    private fun showLoadingState(step: String, detail: String) {
        handler.post {
            layoutRegLoading.visibility    = View.VISIBLE
            webView.visibility             = View.GONE
            layoutWebViewHeader.visibility = View.GONE
            tvRegStep.text                 = step
            tvRegDetail.text               = detail
            tvRegDetail.setTextColor(Color.parseColor("#AAAAAA"))
            progressBarReg.visibility      = View.VISIBLE
            btnRegRetry.visibility         = View.GONE
        }
    }

    private fun showError(title: String, detail: String) {
        handler.post {
            layoutRegLoading.visibility    = View.VISIBLE
            webView.visibility             = View.GONE
            layoutWebViewHeader.visibility = View.GONE
            tvRegStep.text                 = "⚠ $title"
            tvRegDetail.text               = detail
            tvRegDetail.setTextColor(Color.parseColor("#FF4444"))
            progressBarReg.visibility      = View.GONE
            btnRegRetry.visibility         = View.VISIBLE
            btnRegRetry.setOnClickListener { startHyundaiLoginFlow() }
        }
    }

    override fun onBackPressed() {
        AlertDialog.Builder(this)
            .setTitle("등록 취소")
            .setMessage("등록을 취소하시겠습니까?")
            .setPositiveButton("취소") { _, _ -> setResult(RESULT_CANCELED); finish() }
            .setNegativeButton("계속") { d, _ -> d.dismiss() }
            .show()
    }
}
