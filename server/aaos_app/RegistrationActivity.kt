package com.example.carpayin

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 최초 등록 / 카드 재등록 Activity
 *
 * 흐름:
 * 1. [로딩 화면] VIN 읽기 + mTLS 차량 인증 → 토큰 저장
 * 2. [팝업] 번호판 확인 "123가4567이 맞습니까?"
 * 3. [체크] 기어 P 확인
 * 4. [카드 입력 화면] 카드 번호 + 유효기간 + CVC 입력 / 카드 프리뷰
 * 5. 입력값 → Mock PG 시뮬레이션 → payment_method_id 저장
 * 6. 등록 완료 → RESULT_OK → finish()
 */
class RegistrationActivity : Activity() {

    private val TAG = "RegistrationActivity"
    private val handler = Handler(Looper.getMainLooper())

    // ── 로딩 상태 Views ────────────────────────────────────────────────────────
    private lateinit var layoutRegLoading: LinearLayout
    private lateinit var tvRegStep: TextView
    private lateinit var tvRegDetail: TextView
    private lateinit var progressBarReg: ProgressBar
    private lateinit var btnRegRetry: Button

    // ── 카드 입력 상태 Views ───────────────────────────────────────────────────
    private lateinit var layoutRegCardInput: ScrollView
    private lateinit var cardPreviewBody: LinearLayout
    private lateinit var tvCardBrand: TextView
    private lateinit var tvCardNetwork: TextView
    private lateinit var tvCardPreviewNumber: TextView
    private lateinit var tvCardPreviewExpiry: TextView
    private lateinit var tvKeyStatus: TextView
    private lateinit var tvRegVin: TextView
    private lateinit var tvRegPlate: TextView
    private lateinit var layoutCardBrands: LinearLayout
    private lateinit var etCardNumber: EditText
    private lateinit var etCardExpiry: EditText
    private lateinit var etCardCvc: EditText
    private lateinit var btnCardRegister: Button
    private lateinit var btnCardCancel: Button
    private lateinit var btnRegCancel: Button

    // ── 카드사 데이터 ──────────────────────────────────────────────────────────
    data class CardBrand(
        val displayName: String,   // 화면 표시용 (현대카드)
        val shortName: String,     // 카드 프리뷰 브랜드명 (HYUNDAI)
        val bgColor: Int,          // 카드 배경색
        val brandTextColor: Int,   // 브랜드명 색
        val network: String        // VISA / MASTER / AMEX
    )

    private val cardBrands = listOf(
        CardBrand("현대",   "HYUNDAI",  0xFF1A1A2E.toInt(), 0xFFCCCCCC.toInt(), "VISA"),
        CardBrand("KB국민", "KB",       0xFF1A1A1A.toInt(), 0xFFFFCC00.toInt(), "MASTER"),
        CardBrand("신한",   "SHINHAN",  0xFF8B0000.toInt(), 0xFFFFFFFF.toInt(), "VISA"),
        CardBrand("삼성",   "SAMSUNG",  0xFF1428A0.toInt(), 0xFFFFFFFF.toInt(), "MASTER"),
        CardBrand("롯데",   "LOTTE",    0xFF9B0000.toInt(), 0xFFFFFFFF.toInt(), "VISA"),
        CardBrand("우리",   "WOORI",    0xFF004A8F.toInt(), 0xFFFFFFFF.toInt(), "MASTER"),
        CardBrand("하나",   "HANA",     0xFF005C3E.toInt(), 0xFFFFFFFF.toInt(), "VISA")
    )
    private var selectedBrand: CardBrand = cardBrands[0]

    // ── WebView (실서버 연동용) ───────────────────────────────────────────────
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

        // 로딩 Views
        layoutRegLoading = findViewById(R.id.layoutRegLoading)
        tvRegStep        = findViewById(R.id.tvRegStep)
        tvRegDetail      = findViewById(R.id.tvRegDetail)
        progressBarReg   = findViewById(R.id.progressBarReg)
        btnRegRetry      = findViewById(R.id.btnRegRetry)

        // 카드 입력 Views
        layoutRegCardInput   = findViewById(R.id.layoutRegCardInput)
        cardPreviewBody      = findViewById(R.id.cardPreviewBody)
        tvCardBrand          = findViewById(R.id.tvCardBrand)
        tvCardNetwork        = findViewById(R.id.tvCardNetwork)
        tvCardPreviewNumber  = findViewById(R.id.tvCardPreviewNumber)
        tvCardPreviewExpiry  = findViewById(R.id.tvCardPreviewExpiry)
        tvKeyStatus          = findViewById(R.id.tvKeyStatus)
        tvRegVin             = findViewById(R.id.tvRegVin)
        tvRegPlate           = findViewById(R.id.tvRegPlate)
        layoutCardBrands     = findViewById(R.id.layoutCardBrands)
        etCardNumber         = findViewById(R.id.etCardNumber)
        etCardExpiry         = findViewById(R.id.etCardExpiry)
        etCardCvc            = findViewById(R.id.etCardCvc)
        btnCardRegister      = findViewById(R.id.btnCardRegister)
        btnCardCancel        = findViewById(R.id.btnCardCancel)
        btnRegCancel         = findViewById(R.id.btnRegCancel)

        btnRegCancel.setOnClickListener { confirmCancel() }

        webView = findViewById(R.id.webViewPg)

        setupCardPreview()

        val isReregister = intent.getBooleanExtra("reregister", false)
        if (isReregister) showStep("카드 재등록", "인증 정보 확인 중")

        startRegistrationFlow()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 단계 1: mTLS 차량 인증
    // ─────────────────────────────────────────────────────────────────────────

    private fun startRegistrationFlow() {
        showLoadingState("차량 인증 중...", "VIN 및 보안키 확인")

        Thread {
            try {
                vin = VehicleDataManager.readVin(this)
                val certPem = KeystoreManager.generateKeyPairIfNeeded()

                handler.post {
                    tvRegVin.text = "VIN: ${vin.take(5)}•••••••••••"
                    tvKeyStatus.text = "🔑 HSM 보안키 준비 완료"
                    tvKeyStatus.setTextColor(Color.parseColor("#00AA55"))
                }

                val authResult = ApiManager.authenticate(vin, certPem)
                ParkingStateManager.saveTokens(this, authResult.accessToken, authResult.refreshToken)
                plate = authResult.plateNumber

                handler.post { showPlateConfirmDialog(plate) }

            } catch (e: Exception) {
                Log.e(TAG, "차량 인증 실패: ${e.javaClass.simpleName} — ${e.message}")
                handler.post { showError("차량 인증 실패", "${e.javaClass.simpleName}\n재시도 해주세요") }
            }
        }.start()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 단계 2: 번호판 확인 팝업 & 수동 입력
    // ─────────────────────────────────────────────────────────────────────────

    private fun showPlateConfirmDialog(plate: String) {
        showStep("번호판 확인", "국토부 차량 정보 조회 완료")
        progressBarReg.visibility = View.GONE

        // 밝은 배경의 다이얼로그
        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert)
            .setTitle("🚗  차량 번호판 확인")
            .setMessage("차량번호\n\n    $plate\n\n이(가) 맞습니까?")
            .setPositiveButton("맞습니다") { _, _ ->
                confirmPlateAndProceed(plate)
            }
            .setNegativeButton("직접 입력") { _, _ ->
                showManualPlateInputDialog()
            }
            .setCancelable(false)
            .show()
    }

    private fun showManualPlateInputDialog() {
        val etManualPlate = EditText(this).apply {
            hint = "예: 123가4567"
            setSingleLine()
            gravity = android.view.Gravity.CENTER
            setPadding(40, 40, 40, 40)
        }

        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert)
            .setTitle("번호판 수동 입력")
            .setMessage("실제 차량 번호판 정보를 입력해 주세요.")
            .setView(etManualPlate)
            .setPositiveButton("확인") { _, _ ->
                val manualPlate = etManualPlate.text.toString().trim()
                if (manualPlate.isNotEmpty()) {
                    // 정규식을 통한 번호판 기본 형식 검증 (예: 12가3456 또는 123가4567)
                    if (manualPlate.matches(Regex("^[0-9]{2,3}[가-힣][0-9]{4}$"))) {
                        confirmPlateAndProceed(manualPlate)
                    } else {
                        Toast.makeText(this, "올바른 번호판 형식(예: 123가4567)으로 입력해주세요.", Toast.LENGTH_SHORT).show()
                        showManualPlateInputDialog()
                    }
                } else {
                    Toast.makeText(this, "번호판을 입력해야 합니다.", Toast.LENGTH_SHORT).show()
                    showManualPlateInputDialog()
                }
            }
            .setNegativeButton("취소") { _, _ ->
                showPlateConfirmDialog(plate) // 다시 확인 단계로 돌아가기
            }
            .setCancelable(false)
            .show()
    }

    private fun confirmPlateAndProceed(confirmedPlate: String) {
        showLoadingState("번호판 등록 중...", "백엔드 DB 저장")

        Thread {
            try {
                val token = ParkingStateManager.getAccessToken(this) ?: ""
                ApiManager.confirmPlate(vin, confirmedPlate, token)
                ParkingStateManager.savePlateNumber(this, confirmedPlate)

                // 클래스 멤버 변수 갱신
                this.plate = confirmedPlate

                handler.post {
                    tvRegPlate.text = "번호판: $confirmedPlate"
                    checkGearAndProceed()
                }
            } catch (e: Exception) {
                Log.e(TAG, "번호판 등록 실패: ${e.message}")
                handler.post { showError("번호판 등록 실패", "재시도 해주�