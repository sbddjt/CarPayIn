package com.example.carpayin.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.example.carpayin.R
import com.example.carpayin.data.ParkingStateManager
import com.example.carpayin.network.ApiManager

/**
 * Mock PG 카드 등록 WebView 화면
 *
 * 흐름:
 *  1. 백엔드 GET /card/order/{vin} → order_id + Mock PG WebView URL 수신
 *  2. WebView에 Mock PG 카드 입력 페이지 로드
 *  3. 사용자가 카드번호 / 유효기간 / CVC 입력 후 "등록하기"
 *  4. Mock PG → 백엔드 POST /webhook/card (HMAC 검증 + customer_key 저장)
 *  5. Mock PG 페이지 JS → window.Android.onRegistrationComplete() 호출
 *  6. 앱이 카드 정보를 EncryptedSharedPreferences에 저장 → 완료
 *
 * 이후 모든 주차 결제는 백엔드가 customer_key로 자동 처리 (사용자 개입 없음).
 */
class CardRegistrationActivity : Activity() {

    private val TAG = "CardRegActivity"
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView

    private lateinit var vin: String
    private lateinit var accessToken: String
    private lateinit var userName: String

    companion object {
        const val EXTRA_VIN          = "extra_vin"
        const val EXTRA_ACCESS_TOKEN = "extra_access_token"
        const val EXTRA_USER_NAME    = "extra_user_name"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_card_registration)

        webView     = findViewById(R.id.webViewCard)
        progressBar = findViewById(R.id.progressBarCard)
        tvStatus    = findViewById(R.id.tvCardStatus)

        vin         = intent.getStringExtra(EXTRA_VIN)          ?: ""
        accessToken = intent.getStringExtra(EXTRA_ACCESS_TOKEN) ?: ""
        userName    = intent.getStringExtra(EXTRA_USER_NAME)    ?: "고객"

        tvStatus.text = "${userName}님의 결제 카드를 등록해 주세요\n(최초 1회 · 이후 결제는 자동 처리됩니다)"

        setupWebView()
        loadCardRegistrationPage()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WebView 설정
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
        }

        // JS 인터페이스 — Mock PG HTML의 window.Android.onRegistrationComplete() 호출 대상
        webView.addJavascriptInterface(PgJsInterface(), "Android")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                progressBar.visibility = View.VISIBLE
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
            }
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                Log.e(TAG, "WebView 오류: $errorCode $description")
                handler.post {
                    Toast.makeText(this@CardRegistrationActivity,
                        "페이지 로딩 실패. 네트워크를 확인해 주세요.", Toast.LENGTH_LONG).show()
                }
            }
        }
        webView.webChromeClient = WebChromeClient()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 백엔드에서 order_id 발급 → Mock PG WebView 로드
    // ─────────────────────────────────────────────────────────────────────────

    private fun loadCardRegistrationPage() {
        progressBar.visibility = View.VISIBLE
        Thread {
            try {
                val result = ApiManager.fetchCardOrder(vin, accessToken)
                Log.d(TAG, "카드 주문 발급: order_id=${result.orderId} url=${result.pgUrl}")
                handler.post {
                    webView.loadUrl(result.pgUrl)
                }
            } catch (e: Exception) {
                Log.e(TAG, "카드 주문 발급 실패: ${e.message}")
                handler.post {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this, "카드 등록 준비 실패: ${e.message}", Toast.LENGTH_LONG).show()
                    // 등록 실패 시 취소로 복귀
                    setResult(RESULT_CANCELED)
                    finish()
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
         *
         * 백엔드는 이미 /webhook/card로 customer_key를 수신해 저장했고,
         * 앱은 표시용 카드 정보(last_four, brand)만 로컬에 저장.
         */
        @JavascriptInterface
        fun onRegistrationComplete(
            customerKey: String,
            orderId: String,
            lastFour: String,
            cardBrand: String
        ) {
            Log.d(TAG, "[PG 콜백] 카드 등록 완료: ****$lastFour ($cardBrand) order=$orderId")

            // 표시용 카드 정보를 EncryptedSharedPreferences에 저장
            ParkingStateManager.saveCardInfo(
                this@CardRegistrationActivity,
                lastFour,
                cardBrand
            )

            handler.post {
                Toast.makeText(
                    this@CardRegistrationActivity,
                    "$cardBrand ****$lastFour 등록 완료!\n이제 주차는 자동 결제됩니다.",
                    Toast.LENGTH_LONG
                ).show()

                // 잠깐 성공 메시지 보여준 뒤 종료
                handler.postDelayed({
                    setResult(RESULT_OK)
                    finish()
                }, 1_500)
            }
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            // 뒤로가기 시 등록 취소 확인 (optional: AlertDialog 추가 가능)
            setResult(RESULT_CANCELED)
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        webView.destroy()
    }
}
