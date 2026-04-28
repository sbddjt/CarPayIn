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
                handler.post { showError("번호판 등록 실패", "재시도 해주세요") }
            }
        }.start()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 단계 3: 기어 P 확인
    // ─────────────────────────────────────────────────────────────────────────

    private fun checkGearAndProceed() {
        progressBarReg.visibility = View.GONE

        if (!VehicleDataManager.isGearParked(this)) {
            showStep("주차 후 카드를 등록해 주세요", "현재 기어: 주행 중")
            tvRegDetail.setTextColor(Color.parseColor("#FFD700"))
            handler.postDelayed({ checkGearAndProceed() }, 3_000)
            return
        }

        // 에뮬레이터(Pleos Connect): Mock 카드 입력 화면 사용
        // 실서버 연동 시 → createSessionAndOpenPgWebView() 로 교체
        // showCardInputState()
        createSessionAndOpenPgWebView()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 단계 4: 카드 입력 화면 표시
    // ─────────────────────────────────────────────────────────────────────────

    private fun showCardInputState() {
        layoutRegLoading.visibility   = View.GONE
        layoutRegCardInput.visibility = View.VISIBLE

        tvRegVin.text   = "VIN: ${vin.take(5)}•••••••••••"
        tvRegPlate.text = "번호판: $plate"

        // 1. 기존 입력 필드들 숨기기 (UI에서 입력받지 않음)
        etCardNumber.visibility = View.GONE
        etCardExpiry.visibility = View.GONE
        etCardCvc.visibility    = View.GONE
        findViewById<TextView>(R.id.tvKeyStatus).visibility = View.GONE

        // 2. 카드사 선택기 초기화 및 적용
        setupCardBrandSelector()
        applyCardBrand(selectedBrand)

        // 3. 취소 버튼 로직 (기존과 동일)
        btnCardCancel.setOnClickListener {
            confirmCancel()
        }

        // 4. 등록 버튼(btnCardRegister) 클릭 시 바로 웹뷰 세션 생성으로 진입
        btnCardRegister.text = "카드사 연결하기" // 버튼 텍스트 변경
        btnCardRegister.setOnClickListener {
            // 현재 선택된 브랜드의 이름(예: HYUNDAI)을 넘겨줌
            OpenPgWebView(selectedBrand.shortName)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 단계 4-B: order_id 생성 → Mock PG WebView 열기
    // ─────────────────────────────────────────────────────────────────────────

    // brand 파라미터 추가
    private fun createSessionAndOpenPgWebView(brand: String) {
        showLoadingState("카드 등록 준비 중...", "결제 세션 생성")

        Thread {
            try {
                val token = ParkingStateManager.getAccessToken(this) ?: ""
                val orderResult = ApiManager.createCardRegistrationSession(vin, token)
                handler.post {
                    // 생성된 orderId와 선택한 brand 정보를 웹뷰 함수로 전달
                    openPgWebView(orderResult.orderId, brand)
                }
            } catch (e: Exception) {
                Log.e(TAG, "세션 생성 실패: ${e.message}")
                handler.post { showError("세션 생성 실패", "재시도 해주세요") }
            }
        }.start()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openPgWebView(orderId: String, brand: String) { // brand 파라미터 추가
        layoutRegLoading.visibility   = View.GONE
        layoutRegCardInput.visibility = View.GONE
        webView.visibility            = View.VISIBLE

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
        }

        webView.addJavascriptInterface(PgJsInterface(orderId), "Android")

        // 쿼리 파라미터에 brand를 추가하여 파이썬 서버가 어떤 카드사인지 알 수 있게 함
        val pgUrl = "http://10.0.2.2:8000/card-register?order_id=$orderId&brand=$brand"
        Log.d(TAG, "PG WebView URL: $pgUrl")
        webView.loadUrl(pgUrl)
    }

    // ── JavaScript → Android 브릿지 ──────────────────────────────────────────

    inner class PgJsInterface(private val orderId: String) {
        /**
         * Mock PG WebView에서 카드 등록 완료 시 호출
         * window.Android.onRegistrationComplete(customerKey, orderId, lastFour, cardBrand)
         */
        @JavascriptInterface
        fun onRegistrationComplete(
            customerKey: String,
            orderId: String,
            lastFour: String,
            cardBrand: String
        ) {
            Log.d(TAG, "PG 등록 완료 수신: last=$lastFour, brand=$cardBrand")
            handler.post {
                webView.visibility          = View.GONE
                layoutRegLoading.visibility = View.VISIBLE
                showLoadingState("등록 처리 중...", "payment_method_id 수신")
                finishCardRegistrationFromPg(orderId, lastFour, cardBrand)
            }
        }
    }

    private fun finishCardRegistrationFromPg(orderId: String, lastFour: String, cardBrand: String) {
        Thread {
            try {
                val token = ParkingStateManager.getAccessToken(this) ?: ""

                handler.post { showStep("등록 처리 중...", "payment_method_id 수신") }
                val pmResult = ApiManager.getPaymentMethodByOrderId(orderId, token)

                ParkingStateManager.savePaymentMethodId(this, pmResult.paymentMethodId)
                ParkingStateManager.saveCardInfo(this, lastFour, cardBrand)
                ParkingStateManager.setRegistered(this, true)

                Log.d(TAG, "카드 등록 완료: pm=${pmResult.paymentMethodId}, brand=$cardBrand ****$lastFour")
                handler.post { onRegistrationComplete() }

            } catch (e: Exception) {
                Log.e(TAG, "등록 마무리 실패: ${e.message}")
                handler.post { showError("등록 실패", "${e.javaClass.simpleName}\n재시도 해주세요") }
            }
        }.start()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 단계 5: 카드 등록 (Mock PG 시뮬레이션) — 레거시, 현재 미사용
    // ─────────────────────────────────────────────────────────────────────────

    private fun performCardRegistration(cardNumber: String, cardExpiry: String, cardCvc: String) {
        btnCardRegister.isEnabled = false
        btnCardRegister.text = "등록 중..."

        // 카드 입력 화면 숨기고 로딩 표시
        layoutRegCardInput.visibility = View.GONE
        layoutRegLoading.visibility   = View.VISIBLE
        showLoadingState("카드 등록 중...", "Mock PG 빌링키 발급")

        // 카드 표시 정보 미리 추출
        val lastFour  = cardNumber.takeLast(4)
        val brandName = selectedBrand.displayName

        Thread {
            try {
                val token = ParkingStateManager.getAccessToken(this) ?: ""

                // 1. order_id 생성
                handler.post { showStep("카드 등록 중...", "결제 세션 생성") }
                val orderResult = ApiManager.createCardRegistrationSession(vin, token)

                // 2. Mock PG 처리 시뮬레이션
                handler.post { showStep("카드 등록 중...", "PG 서버 처리 중") }
                Thread.sleep(1500)

                // 3. payment_method_id 수신
                handler.post { showStep("카드 등록 완료 처리 중...", "payment_method_id 수신") }
                val pmResult = ApiManager.getPaymentMethodByOrderId(orderResult.orderId, token)

                // 4. 저장 — 카드 브랜드/끝 4자리 포함
                ParkingStateManager.savePaymentMethodId(this, pmResult.paymentMethodId)
                ParkingStateManager.saveCardInfo(this, lastFour, brandName)
                ParkingStateManager.setRegistered(this, true)

                Log.d(TAG, "카드 등록 완료: pm=${pmResult.paymentMethodId}, $brandName ****$lastFour")
                handler.post { onRegistrationComplete() }

            } catch (e: Exception) {
                Log.e(TAG, "카드 등록 실패: ${e.message}")
                handler.post {
                    layoutRegCardInput.visibility = View.VISIBLE
                    layoutRegLoading.visibility   = View.GONE
                    btnCardRegister.isEnabled = true
                    btnCardRegister.text = "등록하기"
                    Toast.makeText(this, "등록 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 단계 6: 등록 완료
    // ─────────────────────────────────────────────────────────────────────────

    private fun onRegistrationComplete() {
        progressBarReg.visibility = View.GONE
        showStep("등록 완료 ✓", "자동 결제가 활성화되었습니다")
        tvRegDetail.setTextColor(Color.parseColor("#00FF88"))

        // 팝업 없이 버튼으로 바로 홈으로 이동
        btnRegRetry.apply {
            visibility = View.VISIBLE
            text       = "홈으로 돌아가기"
            setTextColor(Color.parseColor("#FFFFFF"))
            background = getDrawable(R.drawable.bg_btn_green)
            setOnClickListener {
                setResult(RESULT_OK)
                finish()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 카드사 선택 칩 생성
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupCardBrandSelector() {
        layoutCardBrands.removeAllViews()
        cardBrands.forEach { brand ->
            val chip = TextView(this).apply {
                text = brand.displayName
                textSize = 12f
                setTextColor(if (brand == selectedBrand) Color.WHITE else Color.parseColor("#556677"))
                setBackgroundColor(
                    if (brand == selectedBrand) brand.bgColor else Color.parseColor("#111820")
                )
                setPadding(20, 10, 20, 10)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, 0, 8, 0)
                layoutParams = lp
                setOnClickListener {
                    selectedBrand = brand
                    applyCardBrand(brand)
                    setupCardBrandSelector()   // 선택 상태 갱신
                }
            }
            layoutCardBrands.addView(chip)
        }
    }

    private fun applyCardBrand(brand: CardBrand) {
        cardPreviewBody.setBackgroundColor(brand.bgColor)
        tvCardBrand.text = brand.shortName
        tvCardBrand.setTextColor(brand.brandTextColor)
        tvCardNetwork.text = brand.network
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 카드 프리뷰 실시간 업데이트
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupCardPreview() {
        etCardNumber.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val raw    = s?.toString() ?: ""
                val padded = raw.padEnd(16, '•')
                tvCardPreviewNumber.text = buildString {
                    append(padded.substring(0, 4));  append(" ")
                    append(padded.substring(4, 8));  append(" ")
                    append(padded.substring(8, 12)); append(" ")
                    append(padded.substring(12, 16))
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        etCardExpiry.addTextChangedListener(object : TextWatcher {
            private var isFormatting = false
            override fun afterTextChanged(s: Editable?) {
                if (isFormatting) return
                isFormatting = true
                val raw = s?.toString()?.replace("/", "") ?: ""
                if (raw.length >= 2) {
                    val formatted = raw.substring(0, 2) + "/" + raw.substring(2)
                    s?.replace(0, s.length, formatted)
                }
                tvCardPreviewExpiry.text = if (s.isNullOrEmpty()) "MM/YY" else s.toString()
                isFormatting = false
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI 유틸
    // ─────────────────────────────────────────────────────────────────────────

    /** 로딩 화면 표시 + 프로그레스 ON */
    private fun showLoadingState(step: String, detail: String) {
        layoutRegLoading.visibility   = View.VISIBLE
        layoutRegCardInput.visibility = View.GONE
        tvRegStep.text    = step
        tvRegDetail.text  = detail
        tvRegDetail.setTextColor(Color.parseColor("#555555"))
        progressBarReg.visibility = View.VISIBLE
        btnRegRetry.visibility    = View.GONE
    }

    /** 로딩 화면 안에서 텍스트만 업데이트 */
    private fun showStep(step: String, detail: String) {
        tvRegStep.text   = step
        tvRegDetail.text = detail
        tvRegDetail.setTextColor(Color.parseColor("#555555"))
        btnRegRetry.visibility = View.GONE
    }

    /** 오류 상태 */
    private fun showError(title: String, detail: String) {
        layoutRegLoading.visibility   = View.VISIBLE
        layoutRegCardInput.visibility = View.GONE
        progressBarReg.visibility = View.GONE
        tvRegStep.text   = "⚠  $title"
        tvRegDetail.text = detail
        tvRegDetail.setTextColor(Color.parseColor("#FF4444"))
        btnRegRetry.visibility = View.VISIBLE
        btnRegRetry.setOnClickListener { startRegistrationFlow() }
    }

    /** 입력 필드 흔들기 애니메이션 */
    private fun shake(view: View) {
        view.animate().translationX(8f).setDuration(50)
            .withEndAction {
                view.animate().translationX(-8f).setDuration(50)
                    .withEndAction { view.animate().translationX(0f).setDuration(50) }
            }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 취소 공통 처리
    // ─────────────────────────────────────────────────────────────────────────

    private fun confirmCancel() {
        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert)
            .setTitle("등록 취소")
            .setMessage("카드 등록을 취소하고 돌아가시겠습니까?")
            .setPositiveButton("취소하고 나가기") { _, _ ->
                setResult(RESULT_CANCELED)
                finish()
            }
            .setNegativeButton("계속 등록", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        confirmCancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        webView.destroy()
    }
}