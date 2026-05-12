package com.example.carpayin.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.example.carpayin.R
import com.example.carpayin.data.ParkingStateManager
import com.example.carpayin.network.ApiManager
import java.net.URLEncoder

/**
 * 카드 등록 흐름 — 3단계
 *
 *  STEP 1. 번호판 입력 (layoutPlateInput)
 *          → 입력 확인 후 ParkingStateManager에 저장
 *
 *  STEP 2. 카드사 선택 (layoutBrandSelect)
 *          → 카드사를 누르면 백엔드 GET /card/order → order_id + pg_url 수신
 *
 *  STEP 3. Mock PG WebView (카드 번호 입력)
 *          → 등록 완료 시 JS → window.Android.onRegistrationComplete()
 *          → EncryptedSharedPreferences에 카드 정보 저장 후 RESULT_OK
 *
 *  어느 단계에서든 "← 처음으로" 버튼 → RESULT_CANCELED → 초기화면으로
 */
class CardRegistrationActivity : Activity() {

    private val TAG = "CardRegActivity"
    private val handler = Handler(Looper.getMainLooper())

    // ── Views ────────────────────────────────────────────────────────────────
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var tvStepIndicator: TextView
    private lateinit var btnCancel: TextView  // XML에서 TextView로 선언됨 (AAOS 터치 호환)

    // Step 0
    private lateinit var layoutConsent: LinearLayout
    private lateinit var btnConsentAgree: Button

    // Step 1
    private lateinit var layoutPlateInput: LinearLayout
    private lateinit var etPlateNumber: EditText
    private lateinit var btnPlateNext: Button

    // Step 2
    private lateinit var layoutBrandSelect: LinearLayout
    private lateinit var brandGrid: LinearLayout

    // ── Extras ────────────────────────────────────────────────────────────────
    private lateinit var vin: String
    private lateinit var accessToken: String
    private lateinit var userName: String

    companion object {
        const val EXTRA_VIN          = "extra_vin"
        const val EXTRA_ACCESS_TOKEN = "extra_access_token"
        const val EXTRA_USER_NAME    = "extra_user_name"
    }

    // ── 카드사 목록 ───────────────────────────────────────────────────────────
    data class BrandInfo(
        val name: String,
        val shortName: String,
        val bgColor: Int,
        val textColor: Int,
        val network: String
    )

    private val BRANDS = listOf(
        BrandInfo("현대카드", "HYUNDAI", 0xFF1A1A2E.toInt(), 0xFFCCCCCC.toInt(), "VISA"),
        BrandInfo("KB국민",   "KB",      0xFF1A1200.toInt(), 0xFFFFCC00.toInt(), "MASTER"),
        BrandInfo("신한카드", "SHINHAN", 0xFF5C0000.toInt(), 0xFFFFFFFF.toInt(), "VISA"),
        BrandInfo("삼성카드", "SAMSUNG", 0xFF0A1460.toInt(), 0xFFFFFFFF.toInt(), "MASTER"),
        BrandInfo("롯데카드", "LOTTE",   0xFF6A0000.toInt(), 0xFFFFFFFF.toInt(), "VISA"),
        BrandInfo("우리카드", "WOORI",   0xFF00204A.toInt(), 0xFFFFFFFF.toInt(), "MASTER"),
        BrandInfo("하나카드", "HANA",    0xFF003020.toInt(), 0xFFFFFFFF.toInt(), "VISA"),
    )

    // ── 현재 단계 ─────────────────────────────────────────────────────────────
    private enum class Step { CONSENT, PLATE, BRAND, WEBVIEW }
    private var currentStep = Step.CONSENT

    // ─────────────────────────────────────────────────────────────────────────
    // onCreate
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_card_registration)

        // Views
        webView           = findViewById(R.id.webViewCard)
        progressBar       = findViewById(R.id.progressBarCard)
        tvStatus          = findViewById(R.id.tvCardStatus)
        tvStepIndicator   = findViewById(R.id.tvStepIndicator)
        btnCancel         = findViewById(R.id.btnCancelCard)
        layoutConsent     = findViewById(R.id.layoutConsent)
        btnConsentAgree   = findViewById(R.id.btnConsentAgree)
        layoutPlateInput  = findViewById(R.id.layoutPlateInput)
        etPlateNumber     = findViewById(R.id.etPlateNumber)
        btnPlateNext      = findViewById(R.id.btnPlateNext)
        layoutBrandSelect = findViewById(R.id.layoutBrandSelect)
        brandGrid         = findViewById(R.id.brandGrid)

        // Extras
        vin         = intent.getStringExtra(EXTRA_VIN)          ?: ""
        accessToken = intent.getStringExtra(EXTRA_ACCESS_TOKEN) ?: ""
        userName    = intent.getStringExtra(EXTRA_USER_NAME)    ?: "고객"

        // 이미 저장된 번호판 있으면 미리 채워줌
        val savedPlate = ParkingStateManager.getPlateNumber(this)
        if (!savedPlate.isNullOrEmpty()) {
            etPlateNumber.setText(savedPlate)
        }

        // ── 처음으로 버튼 (어느 단계에서든) ─────────────────────────────────
        btnCancel.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }

        // ── Step 0: 동의 버튼 ────────────────────────────────────────────────
        btnConsentAgree.setOnClickListener {
            goToStep(Step.PLATE)
        }

        // ── Step 1: 번호판 "다음" 버튼 ───────────────────────────────────────
        btnPlateNext.setOnClickListener {
            val plate = etPlateNumber.text.toString().trim()
            if (plate.length < 4) {
                Toast.makeText(this, "번호판을 올바르게 입력해 주세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            hideKeyboard()
            ParkingStateManager.savePlateNumber(this, plate)
            Log.d(TAG, "번호판 저장: $plate")
            goToStep(Step.BRAND)
        }

        // ── WebView + 카드사 그리드 초기화 ───────────────────────────────────
        setupWebView()
        buildBrandGrid()

        // Step 0(동의)부터 시작
        goToStep(Step.CONSENT)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 단계 전환
    // ─────────────────────────────────────────────────────────────────────────

    private fun goToStep(step: Step) {
        currentStep = step
        layoutConsent.visibility     = View.GONE
        layoutPlateInput.visibility  = View.GONE
        layoutBrandSelect.visibility = View.GONE
        webView.visibility           = View.GONE
        progressBar.visibility       = View.GONE

        when (step) {
            Step.CONSENT -> {
                layoutConsent.visibility = View.VISIBLE
                tvStatus.text        = "개인정보 동의"
                tvStepIndicator.text = "1 / 4"
            }
            Step.PLATE -> {
                layoutPlateInput.visibility = View.VISIBLE
                tvStatus.text        = "STEP 1 · 번호판 입력"
                tvStepIndicator.text = "2 / 4"
            }
            Step.BRAND -> {
                layoutBrandSelect.visibility = View.VISIBLE
                tvStatus.text        = "STEP 2 · 카드사 선택"
                tvStepIndicator.text = "3 / 4"
            }
            Step.WEBVIEW -> {
                // WebView는 onPageFinished에서 VISIBLE 처리
                progressBar.visibility = View.VISIBLE
                tvStatus.text        = "STEP 3 · 카드 정보 입력"
                tvStepIndicator.text = "4 / 4"
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 2: 카드사 선택 그리드
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildBrandGrid() {
        brandGrid.removeAllViews()
        val rows = BRANDS.chunked(2)
        rows.forEach { rowBrands ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.setMargins(0, 0, 0, dp(12)) }
            }
            rowBrands.forEach { brand -> row.addView(makeBrandCard(brand)) }
            if (rowBrands.size == 1) {
                row.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
            }
            brandGrid.addView(row)
        }
    }

    private fun makeBrandCard(brand: BrandInfo): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.BOTTOM or Gravity.START
            background  = android.graphics.drawable.GradientDrawable().apply {
                setColor(brand.bgColor)
                cornerRadius = dp(14).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(100), 1f)
                .also { it.setMargins(0, 0, 0, 0) }
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val tvShort = TextView(this).apply {
            text = brand.shortName
            setTextColor(brand.textColor)
            textSize = 9f
            letterSpacing = 0.12f
        }
        val tvName = TextView(this).apply {
            text = brand.name
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(4) }
        }
        val tvNet = TextView(this).apply {
            text = brand.network
            setTextColor(Color.parseColor("#80FFFFFF"))
            textSize = 9f
        }

        card.addView(tvShort)
        card.addView(tvName)
        card.addView(tvNet)
        card.setOnClickListener { onBrandSelected(brand) }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .also { it.setMargins(0, 0, dp(10), 0) }
            addView(card.also {
                it.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(100))
            })
        }
    }

    private fun onBrandSelected(brand: BrandInfo) {
        tvStatus.text = "${brand.name} 결제창 불러오는 중..."
        goToStep(Step.WEBVIEW)
        loadCardRegistrationPage(brand)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 3: WebView 설정
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled    = true
            domStorageEnabled    = true
            loadWithOverviewMode = true
            useWideViewPort      = true
        }

        webView.addJavascriptInterface(PgJsInterface(), "Android")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?,
                                       favicon: android.graphics.Bitmap?) {
                progressBar.visibility = View.VISIBLE
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
                webView.visibility     = View.VISIBLE
            }
            override fun onReceivedError(view: WebView?, errorCode: Int,
                                         description: String?, failingUrl: String?) {
                Log.e(TAG, "WebView 오류: $errorCode $description")
                handler.post {
                    Toast.makeText(this@CardRegistrationActivity,
                        "페이지 로딩 실패. 서버를 확인해 주세요.", Toast.LENGTH_LONG).show()
                    goToStep(Step.BRAND)
                }
            }
        }
        webView.webChromeClient = WebChromeClient()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 백엔드 order_id 발급 → Mock PG WebView 로드
    // ─────────────────────────────────────────────────────────────────────────

    private fun loadCardRegistrationPage(brand: BrandInfo) {
        Thread {
            try {
                val result = ApiManager.fetchCardOrder(accessToken)
                Log.d(TAG, "카드 주문 발급: order_id=${result.orderId} brand=${brand.name}")

                val encodedBrand = URLEncoder.encode(brand.name, "UTF-8")
                // 에뮬레이터에서 localhost → 10.0.2.2 치환 (호스트 PC Mock PG 접근용)
                val fixedPgUrl = result.pgUrl.replace("localhost", "10.0.2.2")
                val separator  = if (fixedPgUrl.contains("?")) "&" else "?"
                val pgUrl      = "$fixedPgUrl${separator}card_brand=$encodedBrand"

                handler.post {
                    tvStatus.text = "STEP 3 · 카드 정보 입력"
                    webView.loadUrl(pgUrl)
                }
            } catch (e: Exception) {
                Log.e(TAG, "카드 주문 발급 실패: ${e.message}")
                handler.post {
                    Toast.makeText(this,
                        "카드 등록 준비 실패: ${e.message}", Toast.LENGTH_LONG).show()
                    goToStep(Step.BRAND)
                }
            }
        }.start()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Mock PG → JS 인터페이스
    // ─────────────────────────────────────────────────────────────────────────

    inner class PgJsInterface {
        /**
         * Mock PG HTML에서 등록 완료 시:
         *   window.Android.onRegistrationComplete(customerKey, orderId, lastFour, cardBrand)
         */
        @JavascriptInterface
        fun onRegistrationComplete(
            customerKey: String,
            orderId: String,
            lastFour: String,
            cardBrand: String
        ) {
            Log.d(TAG, "[PG 콜백] 카드 등록 완료: ****$lastFour ($cardBrand)")

            ParkingStateManager.saveCardInfo(
                this@CardRegistrationActivity, lastFour, cardBrand)

            handler.post {
                Toast.makeText(
                    this@CardRegistrationActivity,
                    "$cardBrand ****$lastFour 등록 완료!\n이제 주차는 자동 결제됩니다.",
                    Toast.LENGTH_LONG
                ).show()
                handler.postDelayed({
                    setResult(RESULT_OK)
                    finish()
                }, 1_500)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 뒤로가기 — 단계별 처리
    // ─────────────────────────────────────────────────────────────────────────

    override fun onBackPressed() {
        when (currentStep) {
            Step.CONSENT -> {
                // 동의 화면에서 뒤로 → 초기화면으로
                setResult(RESULT_CANCELED)
                super.onBackPressed()
            }
            Step.PLATE   -> {
                // 번호판 입력에서 뒤로 → 동의 화면으로
                goToStep(Step.CONSENT)
            }
            Step.BRAND   -> {
                // 카드사 선택에서 뒤로 → 번호판 입력으로
                goToStep(Step.PLATE)
            }
            Step.WEBVIEW -> {
                // 카드 입력 WebView에서 뒤로 → 카드사 선택으로
                if (webView.canGoBack()) webView.goBack()
                else goToStep(Step.BRAND)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        webView.destroy()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 유틸
    // ─────────────────────────────────────────────────────────────────────────

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(),
            resources.displayMetrics
        ).toInt()

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }
}
