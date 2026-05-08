package com.example.carpayin

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import java.net.URLEncoder

/**
 * 현대 개발자 포털 OAuth 2.0 Authorization Code 플로우 매니저
 *
 * ■ 흐름 (아키텍처 최종흐름.txt § 최초 등록)
 *   1. [AAOS 앱] getAuthorizationUrl() → 현대 로그인 URL 생성
 *   2. [AAOS 앱] loadIntoWebView() → WebView에 현대 계정 로그인 화면 로드
 *   3. [사용자] 현대 계정 아이디/비밀번호 입력 후 로그인
 *   4. [현대 서버] redirect_uri 로 리다이렉트 → URL에 code= 파라미터 포함
 *   5. [AAOS 앱] shouldOverrideUrlLoading()에서 code 추출 → onAuthCodeReceived 콜백
 *   6. [CarPayIn 백엔드] /auth/hyundai/callback 으로 code 전달
 *      → Lambda: 현대 토큰 발급 API → user_id + 전화번호 + VIN 목록 조회
 *      → CarPayIn 액세스/리프레시 토큰 반환
 *
 * ■ 현대 개발자 포털 등록 필요 항목
 *   - Client ID  (HYUNDAI_CLIENT_ID)
 *   - Redirect URI  (HYUNDAI_REDIRECT_URI) — 포털에 사전 등록 필수
 *   - 권한 스코프: openid profile car:read
 *
 * ■ TODO: developers.hyundai.com 에서 발급받은 실제 값으로 교체
 *   https://developers.hyundai.com/web/v1/hyundai/guide_api
 */
object HyundaiOAuthManager {

    private const val TAG = "HyundaiOAuthManager"

    // ─── 현대 개발자 포털 설정값 (TODO: 실제 값으로 교체) ─────────────────────
    // 포털에서 발급받은 Client ID (프로젝트 등록 후 서비스 콘솔에서 확인)
    private const val HYUNDAI_CLIENT_ID = "26b816d9-7764-42bd-bdbf-ff49f2e33098"

    // 포털에서 등록한 Redirect URI
    // Android AAOS 앱의 경우 일반적으로 Custom Scheme 사용:
    //   carpayin://auth/hyundai/callback
    // 또는 포털에서 허용하는 다른 URI 사용
    // 에뮬레이터에서 호스트 PC localhost = 10.0.2.2
    // 현대 개발자 포털에는 http://localhost:8080/auth/redirect 로 등록되어 있으므로
    // shouldOverrideUrlLoading에서 localhost 또는 10.0.2.2 둘 다 잡아야 함
    private const val HYUNDAI_REDIRECT_URI = "http://localhost:8080/auth/redirect"
    private const val HYUNDAI_REDIRECT_URI_EMU = "http://10.0.2.2:8080/auth/redirect"

    // OAuth 2.0 Authorization Endpoint (현대 통합계정)
    // TODO: developers.hyundai.com → API 가이드에서 실제 URL 확인
    // 참고: 유럽 버전은 "https://accounts.hyundai.com/auth/realms/HyundaiAccount/..."
    // 국내 버전은 현대 개발자 포털 문서에서 확인 필요
    private const val AUTHORIZATION_ENDPOINT =
        "https://accounts.hyundai.com/auth/realms/HyundaiAccount/protocol/openid-connect/auth"

    // 요청할 권한 스코프 (개발자 포털에서 승인받은 스코프만 사용 가능)
    // openid  - 기본 사용자 인증
    // profile - 사용자 프로필 (user_id, 이름 등)
    // car:read - 차량 리스트 및 VIN 조회
    // phone   - 전화번호 조회 (OTP 발송용)
    private const val SCOPE = "openid profile car:read phone"

    // ─── 상태값 ──────────────────────────────────────────────────────────────

    /** Authorization Code 수신 콜백 — RegistrationActivity에서 등록 */
    var onAuthCodeReceived: ((code: String) -> Unit)? = null

    /** 로그인 취소/에러 콜백 */
    var onAuthError: ((reason: String) -> Unit)? = null

    // CSRF 방지용 state 값 (요청마다 랜덤 생성)
    private var pendingState: String = ""

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * 현대 계정 로그인 URL 생성
     *
     * @return 로그인 WebView에 로드할 Authorization URL
     *
     * 생성 URL 예시:
     * https://accounts.hyundai.com/...?
     *   response_type=code
     *   &client_id=YOUR_CLIENT_ID
     *   &redirect_uri=carpayin%3A%2F%2F...
     *   &scope=openid+profile+car%3Aread+phone
     *   &state=RANDOM_STATE
     */
    fun getAuthorizationUrl(): String {
        // CSRF 방지용 state 생성 (요청마다 새로 생성)
        pendingState = generateRandomState()

        val params = buildString {
            append("response_type=code")
            append("&client_id=${URLEncoder.encode(HYUNDAI_CLIENT_ID, "UTF-8")}")
            append("&redirect_uri=${URLEncoder.encode(HYUNDAI_REDIRECT_URI, "UTF-8")}")
            append("&scope=${URLEncoder.encode(SCOPE, "UTF-8")}")
            append("&state=${URLEncoder.encode(pendingState, "UTF-8")}")
            // 현대 계정 로그인 언어 설정 (한국어)
            append("&ui_locales=ko")
            // PKCE 미사용 (백엔드에서 client_secret 보유 → Authorization Code 방식 충분)
        }

        val url = "$AUTHORIZATION_ENDPOINT?$params"
        Log.d(TAG, "Authorization URL 생성 완료 (state: ${pendingState.take(8)}…)")
        return url
    }

    /**
     * WebView에 현대 로그인 화면을 로드하고 Authorization Code를 가로챕니다.
     *
     * @param webView 로그인 화면을 표시할 WebView (RegistrationActivity에서 전달)
     * @param context Context
     *
     * 동작:
     *  - 쿠키를 초기화해 이전 세션이 자동 로그인되지 않도록 합니다.
     *  - shouldOverrideUrlLoading에서 redirect_uri 리다이렉트를 가로채
     *    code 파라미터를 추출하고 onAuthCodeReceived를 호출합니다.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun loadIntoWebView(webView: WebView, context: Context) {
        // 이전 현대 세션 쿠키 초기화 (다른 계정 로그인 방지)
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }

        webView.settings.apply {
            javaScriptEnabled    = true
            domStorageEnabled    = true
            loadWithOverviewMode = true
            useWideViewPort      = true
            // 현대 로그인 페이지는 User-Agent 체크를 할 수 있으므로 기본값 유지
        }

        webView.setBackgroundColor(Color.WHITE)

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                Log.d(TAG, "페이지 이동: ${url.take(80)}…")

                // redirect_uri 로 리다이렉트됐는지 확인
                // 에뮬레이터는 10.0.2.2, 실기기는 localhost 둘 다 처리
                return if (url.startsWith(HYUNDAI_REDIRECT_URI) ||
                           url.startsWith(HYUNDAI_REDIRECT_URI_EMU)) {
                    handleRedirect(url)
                    true  // WebView가 이 URL을 로드하지 않도록 차단
                } else {
                    false // 현대 로그인 페이지 내부 네비게이션은 정상 처리
                }
            }

            @Deprecated("Deprecated in API 24")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                return if (url.startsWith(HYUNDAI_REDIRECT_URI) ||
                           url.startsWith(HYUNDAI_REDIRECT_URI_EMU)) {
                    handleRedirect(url)
                    true
                } else {
                    false
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                Log.d(TAG, "페이지 로딩 완료: ${url.take(60)}…")
            }

            override fun onReceivedError(
                view: WebView,
                errorCode: Int,
                description: String,
                failingUrl: String
            ) {
                Log.e(TAG, "WebView 에러: [$errorCode] $description — $failingUrl")
                onAuthError?.invoke("로그인 페이지 로딩 실패 ($errorCode): $description")
            }
        }

        val authUrl = getAuthorizationUrl()
        webView.loadUrl(authUrl)
        Log.d(TAG, "현대 로그인 WebView 로딩 시작")
    }

    /**
     * 현재 로그인 세션 초기화 (로그아웃 또는 계정 변경 시 사용)
     */
    fun clearSession() {
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }
        pendingState = ""
        Log.d(TAG, "OAuth 세션 초기화 완료")
    }

    // ─── Internal ────────────────────────────────────────────────────────────

    /**
     * Redirect URI 리다이렉트를 처리합니다.
     *
     * 성공 예시:
     *   carpayin://auth/hyundai/callback?code=AUTHCODE&state=RANDOMSTATE
     *
     * 에러 예시:
     *   carpayin://auth/hyundai/callback?error=access_denied&error_description=...
     */
    private fun handleRedirect(url: String) {
        Log.d(TAG, "Redirect URI 수신 — 파싱 시작")

        // URL 파라미터 추출
        val queryString = url.substringAfter("?", "")
        val params = queryString.split("&").associate { pair ->
            val (k, v) = pair.split("=", limit = 2).let {
                Pair(it[0], if (it.size > 1) it[1] else "")
            }
            k to java.net.URLDecoder.decode(v, "UTF-8")
        }

        // 에러 응답 처리
        if (params.containsKey("error")) {
            val error = params["error"] ?: "unknown_error"
            val description = params["error_description"] ?: "인증 오류가 발생했습니다"
            Log.e(TAG, "OAuth 에러: $error — $description")
            onAuthError?.invoke("현대 로그인 오류: $description")
            return
        }

        // state 검증 (CSRF 방지)
        val returnedState = params["state"] ?: ""
        if (returnedState != pendingState) {
            Log.e(TAG, "State 불일치! 예상: ${pendingState.take(8)}… 수신: ${returnedState.take(8)}…")
            onAuthError?.invoke("보안 검증 실패 — 다시 시도해 주세요")
            return
        }

        // Authorization Code 추출
        val code = params["code"]
        if (code.isNullOrBlank()) {
            Log.e(TAG, "Authorization Code 없음 — URL: $url")
            onAuthError?.invoke("인증 코드를 받지 못했습니다")
            return
        }

        Log.d(TAG, "Authorization Code 수신 완료: ${code.take(8)}…")
        pendingState = ""  // state 소비 (재사용 방지)
        onAuthCodeReceived?.invoke(code)
    }

    /**
     * CSRF 방지용 무작위 state 문자열 생성 (32바이트 hex)
     */
    private fun generateRandomState(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ─── 디버그 헬퍼 ─────────────────────────────────────────────────────────

    /** 에뮬레이터 테스트용 Mock Auth Code (실기기에서는 사용 안 함) */
    fun injectMockAuthCode(code: String = "MOCK_AUTH_CODE_FOR_EMULATOR") {
        Log.w(TAG, "⚠ Mock Auth Code 주입 (에뮬레이터 전용)")
        onAuthCodeReceived?.invoke(code)
    }
}
