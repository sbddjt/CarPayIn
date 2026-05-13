package com.example.carpayin.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
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
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
import com.example.carpayin.network.SessionExpiredException

class CardRegistrationActivity : Activity() {

    private val TAG = "CardRegActivity"
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var tvStepIndicator: TextView
    private lateinit var btnCancel: TextView
    private lateinit var btnPrevStep: TextView

    private lateinit var layoutConsent: LinearLayout
    private lateinit var btnConsentAgree: Button

    private lateinit var layoutPlateInput: LinearLayout
    private lateinit var etPlateNumber: EditText
    private lateinit var btnPlateNext: Button

    private lateinit var layoutBrandSelect: LinearLayout
    private lateinit var brandGrid: LinearLayout

    private lateinit var accessToken: String
    private lateinit var userName: String

    companion object {
        const val EXTRA_ACCESS_TOKEN = "extra_access_token"
        const val EXTRA_USER_NAME    = "extra_user_name"
    }

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

    private enum class Step { CONSENT, PLATE, BRAND, WEBVIEW }
    private var currentStep = Step.CONSENT

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_card_registration)

        webView           = findViewById(R.id.webViewCard)
        progressBar       = findViewById(R.id.progressBarCard)
        tvStatus          = findViewById(R.id.tvCardStatus)
        tvStepIndicator   = findViewById(R.id.tvStepIndicator)
        btnCancel         = findViewById(R.id.btnCancelCard)
        btnPrevStep       = findViewById(R.id.btnPrevStep)
        layoutConsent     = findViewById(R.id.layoutConsent)
        btnConsentAgree   = findViewById(R.id.btnConsentAgree)
        layoutPlateInput  = findViewById(R.id.layoutPlateInput)
        etPlateNumber     = findViewById(R.id.etPlateNumber)
        btnPlateNext      = findViewById(R.id.btnPlateNext)
        layoutBrandSelect = findViewById(R.id.layoutBrandSelect)
        brandGrid         = findViewById(R.id.brandGrid)

        accessToken = intent.getStringExtra(EXTRA_ACCESS_TOKEN) ?: ""
        userName    = intent.getStringExtra(EXTRA_USER_NAME)    ?: "고객"

        val savedPlate = ParkingStateManager.getPlateNumber(this)
        if (!savedPlate.isNullOrEmpty()) {
            etPlateNumber.setText(savedPlate)
        }

        btnCancel.setOnClickListener {
            // 어떤 단계에 있든 즉시 메인(로그인됨/카드 미등록) 화면으로 복귀
            returnToOAuthPending()
        }

        btnPrevStep.setOnClickListener {
            // 한 단계만 되돌아감 — onBackPressed() 와 동일한 동작을 재사용
            goPrevStep()
        }

        btnConsentAgree.setOnClickListener { goToStep(Step.PLATE) }

        btnPlateNext.setOnClickListener {
            val plate = etPlateNumber.text.toString().trim()
            if (plate.length < 4) {
                Toast.makeText(this, "번호판을 올바르게 입력해 주세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            hideKeyboard()
            ParkingStateManager.savePlateNumber(this, plate)
            goToStep(Step.BRAND)
        }

        setupWebView()
        buildBrandGrid()

        // 시작은 무조건 Step 0(동의)부터
        goToStep(Step.CONSENT)
    }

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
                webView.visibility = View.INVISIBLE
                progressBar.visibility = View.VISIBLE
                tvStatus.text        = "STEP 3 · 카드 정보 입력"
                tvStepIndicator.text = "4 / 4"
            }
        }

        // 첫 단계(CONSENT)에서는 '이전'이 의미 없으므로 숨기고,
        // 그 외 단계에서는 헤더에 '← 이전' 버튼을 노출한다.
        btnPrevStep.visibility = if (step == Step.CONSENT) View.GONE else View.VISIBLE
    }

    /**
     * '← 이전' / 시스템 백 버튼 공통 처리.
     * 단계에 맞춰 한 단계만 되돌아간다. WEBVIEW 단계에서는 WebView 의
     * 내부 히스토리가 있으면 그쪽을 먼저 소비한다.
     */
    private fun goPrevStep() {
        when (currentStep) {
            Step.CONSENT -> returnToOAuthPending()  // 첫 단계에서는 '처음으로'와 동일
            Step.PLATE   -> goToStep(Step.CONSENT)
            Step.BRAND   -> goToStep(Step.PLATE)
            Step.WEBVIEW -> {
                if (webView.canGoBack()) webView.goBack() else goToStep(Step.BRAND)
            }
        }
    }

    private fun buildBrandGrid() {
        brandGrid.removeAllViews()
        val rows = BRANDS.chunked(2)
        rows.forEach { rowBrands ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.setMargins(0, 0, 0, dp(12)) }
            }
            rowBrands.forEach { brand -> row.addView(makeBrandCard(brand)) }
            if (rowBrands.size == 1) {
                row.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
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
            layoutParams = LinearLayout.LayoutParams(0, dp(100), 1f).also { it.setMargins(0, 0, 0, 0) }
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
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(4) }
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
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also { it.setMargins(0, 0, dp(10), 0) }
            addView(card.also { it.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(100)) })
        }
    }

    private fun onBrandSelected(brand: BrandInfo) {
        tvStatus.text = "${brand.name} 결제창 불러오는 중..."
        goToStep(Step.WEBVIEW)
        loadCardRegistrationPage(brand)
    }

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
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                progressBar.visibility = View.VISIBLE
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
                webView.visibility     = View.VISIBLE
            }
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                handler.post {
                    Toast.makeText(this@CardRegistrationActivity, "페이지 로딩 실패. 서버를 확인해 주세요.", Toast.LENGTH_LONG).show()
                    goToStep(Step.BRAND)
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame != true) return
                Log.e(TAG, "WebView load failed: ${error?.description} url=${request.url}")
                handler.post {
                    Toast.makeText(this@CardRegistrationActivity, "페이지 로딩 실패: ${error?.description}", Toast.LENGTH_LONG).show()
                    goToStep(Step.BRAND)
                }
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                if (request?.isForMainFrame != true) return
                Log.e(TAG, "WebView HTTP ${errorResponse?.statusCode} url=${request.url}")
                handler.post {
                    Toast.makeText(this@CardRegistrationActivity, "PG 페이지 오류: HTTP ${errorResponse?.statusCode}", Toast.LENGTH_LONG).show()
                    goToStep(Step.BRAND)
                }
            }
        }
        webView.webChromeClient = WebChromeClient()
    }

    private fun loadCardRegistrationPage(brand: BrandInfo) {
        val currentPlate = ParkingStateManager.getPlateNumber(this) ?: ""

        Thread {
            try {
                val result = ApiManager.withAutoRefresh(this) { token ->
                    try {
                        ApiManager.createCardOrder(
                            plate = currentPlate,
                            bankName = brand.name,
                            agreeTerms = true,
                            accessToken = token
                        )
                    } catch (e: RuntimeException) {
                        if (e.message.orEmpty().contains("HTTP 405")) {
                            ApiManager.fetchCardOrderLegacy(token)
                        } else {
                            throw e
                        }
                    }
                }
                val fixedPgUrl = normalizePgUrlForEmulator(result.pgUrl)
                Log.d(TAG, "Loading PG url: $fixedPgUrl")
                handler.post {
                    tvStatus.text = "STEP 3 · 카드 정보 입력"
                    webView.loadUrl(fixedPgUrl)
                }
            } catch (e: SessionExpiredException) {
                handler.post {
                    Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
                    setResult(RESULT_CANCELED)
                    finish()
                }
            } catch (e: Exception) {
                Log.e(TAG, "createCardOrder failed", e)
                handler.post {
                    Toast.makeText(this, "카드 등록 준비 실패: ${e.message}", Toast.LENGTH_LONG).show()
                    goToStep(Step.BRAND)
                }
            }
        }.start()
    }

    private fun normalizePgUrlForEmulator(pgUrl: String): String {
        return pgUrl
            .replace("://localhost:", "://10.0.2.2:")
            .replace("://127.0.0.1:", "://10.0.2.2:")
    }

    inner class PgJsInterface {
        @JavascriptInterface
        fun onRegistrationComplete(customerKey: String, orderId: String, lastFour: String, cardBrand: String) {
            ParkingStateManager.saveCardInfo(this@CardRegistrationActivity, lastFour, cardBrand)
            handler.post {
                Toast.makeText(this@CardRegistrationActivity, "$cardBrand ****$lastFour 등록 완료!\n이제 주차는 자동 결제됩니다.", Toast.LENGTH_LONG).show()
                handler.postDelayed({
                    setResult(RESULT_OK)
                    finish()
                }, 1_500)
            }
        }
    }

    override fun onBackPressed() {
        // 시스템 백 버튼도 '← 이전' 버튼과 동일하게 동작
        goPrevStep()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        webView.destroy()
    }

    private fun returnToOAuthPending() {
        // OAuth(마이현대) 로그인 상태는 유지하고 카드 등록 상태만 해제한다.
        ParkingStateManager.setOAuthComplete(this, true)
        ParkingStateManager.setRegistered(this, false)
        // MainActivity.onActivityResult(101, RESULT_CANCELED) 가
        //  showOAuthPendingState() 로 화면을 복귀시켜 주므로,
        //  여기서 별도로 startActivity 를 호출하면 안 된다.
        //  (FLAG_ACTIVITY_CLEAR_TOP + startActivityForResult 와 충돌해
        //   onActivityResult 가 사라지거나 화면이 두 번 그려지는 문제가 있었다.)
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }
}
