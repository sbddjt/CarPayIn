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
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.example.carpayin.R
import com.example.carpayin.data.ParkingStateManager
import com.example.carpayin.network.ApiManager
import java.net.URLEncoder

/**
 * Mock PG 카드 등록 WebView 화면
 *
 * 흐름:
 *  1. 카드사 선택 화면 (네이티브 UI)
 *  2. 선택한 카드사 → 백엔드 GET /card/order/{vin} → order_id + pg_url 수신
 *  3. pg_url에 card_brand 파라미터 추가 → WebView 로드
 *     (Mock PG가 해당 카드사 스타일로 페이지를 렌더링)
 *  4. 사용자가 카드번호 / 유효기간 / CVC 입력 후 "등록하기"
 *  5. Mock PG → 백엔드 POST /webhook/card (HMAC 검증 + customer_key 저장)
 *  6. Mock PG 페이지 JS → window.Android.onRegistrationComplete() 호출
 *  7. 앱이 카드 정보를 EncryptedSharedPreferences에 저장 → 완료
 */
class CardRegistrationActivity : Activity() {

    private val TAG = "CardRegActivity"
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var layoutBrandSelect: LinearLayout
    private lateinit var brandGrid: LinearLayout

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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_card_registration)

        webView           = findViewById(R.id.webViewCard)
        progressBar       = findViewById(R.id.progressBarCard)
        tvStatus          = findViewById(R.id.tvCardStatus)
        layoutBrandSelect = findViewById(R.id.layoutBrandSelect)
        brandGrid         = findViewById(R.id.brandGrid)

        vin         = intent.getStringExtra(EXTRA_VIN)          ?: ""
        accessToken = intent.getStringExtra(EXTRA_ACCESS_TOKEN) ?: ""
        userName    = intent.getStringExtra(EXTRA_USER_NAME)    ?: "고객"

        tvStatus.text = "${userName}님, 결제 카드사를 선택해 주세요"

        setupWebView()
        buildBrandGrid()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 카드사 선택 그리드 (2열 구성)
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildBrandGrid() {
        brandGrid.removeAllViews()

        // 2열씩 행으로 구성
        val rows = BRANDS.chunked(2)
        rows.forEach { rowBrands ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.setMargins(0, 0, 0, dp(12)) }
            }
            rowBrands.forEach { brand ->
                row.addView(makeBrandCard(brand))
            }
            // 홀수 개일 때 마지막 행 빈 칸 채우기
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
            setBackgroundColor(brand.bgColor)

            val r = dp(14).toFloat()
            // API 21+ 에서 outlineProvider로 라운드 처리
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(brand.bgColor)
                cornerRadius = r
            }

            layoutParams = LinearLayout.LayoutParams(0,
                dp(100), 1f).also {
                it.setMargins(if (indexOfChild(this) == 0) 0 else dp(10), 0, 0, 0)
            }
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        // 카드사 짧은 이름 (상단)
        val tvShort = TextView(this).apply {
            text      = brand.shortName
            setTextColor(brand.textColor)
            textSize  = 9f
            letterSpacing = 0.12f
        }

        // 카드사 풀 이름 (하단)
        val tvName = TextView(this).apply {
            text      = brand.name
            setTextColor(0xFFFFFFFF.toInt())
            textSize  = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(4) }
        }

        // 네트워크 라벨 (우하단)
        val tvNet = TextView(this).apply {
            text     = brand.network
            setTextColor(Color.parseColor("#80FFFFFF"))
            textSize = 9f
        }

        card.addView(tvShort)
        card.addView(tvName)
        card.addView(tvNet)

        card.setOnClickListener { onBrandSelected(brand) }

        // 여백을 위한 wrapper
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
        tvStatus.text = "${brand.name} 카드 등록 화면을 불러오는 중..."
        layoutBrandSelect.visibility = View.GONE
        progressBar.visibility       = View.VISIBLE
        loadCardRegistrationPage(brand)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WebView 설정
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled   = true
            domStorageEnabled   = true
            loadWithOverviewMode = true
            useWideViewPort     = true
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
                        "페이지 로딩 실패. 네트워크를 확인해 주세요.", Toast.LENGTH_LONG).show()
                    // 브랜드 선택 화면으로 복귀
                    layoutBrandSelect.visibility = View.VISIBLE
                    webView.visibility           = View.GONE
                    tvStatus.text = "${userName}님, 결제 카드사를 선택해 주세요"
                }
            }
        }
        webView.webChromeClient = WebChromeClient()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 백엔드 order_id 발급 → 선택한 카드사 파라미터 포함 Mock PG WebView 로드
    // ─────────────────────────────────────────────────────────────────────────

    private fun loadCardRegistrationPage(brand: BrandInfo) {
        Thread {
            try {
                val result = ApiManager.fetchCardOrder(vin, accessToken)
                Log.d(TAG, "카드 주문 발급: order_id=${result.orderId} brand=${brand.name}")

                // Mock PG URL에 card_brand 파라미터 추가 → PG가 해당 카드사 UI로 렌더링
                val encodedBrand = URLEncoder.encode(brand.name, "UTF-8")
                val pgUrl = "${result.pgUrl}&card_brand=$encodedBrand"

                handler.post {
                    tvStatus.text = "${brand.name} 카드 정보를 입력해 주세요"
                    webView.loadUrl(pgUrl)
                }
            } catch (e: Exception) {
                Log.e(TAG, "카드 주문 발급 실패: ${e.message}")
                handler.post {
                    progressBar.visibility       = View.GONE
                    layoutBrandSelect.visibility = View.VISIBLE
                    webView.visibility           = View.GONE
                    tvStatus.text = "${userName}님, 결제 카드사를 선택해 주세요"
                    Toast.makeText(this,
                        "카드 등록 준비 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Mock PG → JS 인터페이스
    // ─────────────────────────────────────────────────────────────────────────

    inner class PgJsInterface {
        /**
         * Mock PG HTML에서 카드 등록 완료 시 호출:
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
    // 뒤로가기 — WebView 중이면 브랜드 선택으로 복귀
    // ─────────────────────────────────────────────────────────────────────────

    override fun onBackPressed() {
        when {
            webView.visibility == View.VISIBLE && webView.canGoBack() -> webView.goBack()
            webView.visibility == View.VISIBLE -> {
                // WebView 닫고 브랜드 선택으로 돌아가기
                webView.visibility           = View.GONE
                layoutBrandSelect.visibility = View.VISIBLE
                tvStatus.text = "${userName}님, 결제 카드사를 선택해 주세요"
            }
            else -> {
                setResult(RESULT_CANCELED)
                super.onBackPressed()
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
}
