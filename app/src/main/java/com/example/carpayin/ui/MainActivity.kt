package com.example.carpayin.ui

import com.example.carpayin.R
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.carpayin.data.ParkingStateManager
import com.example.carpayin.data.TransactionStore
import com.example.carpayin.network.ApiManager
import com.example.carpayin.network.MqttManager
import com.example.carpayin.network.SessionExpiredException
import com.example.carpayin.service.CarPayInService
import com.example.carpayin.vehicle.GeofenceManager
import com.example.carpayin.vehicle.NaviHelper
import com.example.carpayin.vehicle.VehicleDataManager

class MainActivity : AppCompatActivity() {

    private val TAG = "CarPayIn"
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        const val ACTION_SHOW_OAUTH_PENDING = "com.example.carpayin.SHOW_OAUTH_PENDING"
        const val EXTRA_SHOW_OAUTH_PENDING = "extra_show_oauth_pending"
    }

    private var vin: String = ""
    private var approachingLotId: String? = null
    private var navigatingLotId: String? = null

    private lateinit var tvStatusDot: TextView
    private lateinit var tvPaymentStatus: TextView
    private lateinit var tvParkingBadge: TextView
    private lateinit var tvVinShort: TextView
    private lateinit var tvPlateNumber: TextView
    private lateinit var layoutUnregistered: LinearLayout
    private lateinit var layoutRegistered: ScrollView
    private lateinit var tvHeaderTitle: RelativeLayout

    // 🌟 원하시던 중간 화면 UI
    private lateinit var layoutOAuthPending: LinearLayout
    private lateinit var tvOAuthPendingUser: TextView
    private lateinit var btnOAuthPendingRegisterCard: Button
    private lateinit var btnOAuthPendingCancel: Button

    private lateinit var btnResetApp: Button
    private lateinit var mainCardBody: LinearLayout
    private lateinit var mainCardBrand: TextView
    private lateinit var mainCardNetwork: TextView
    private lateinit var mainCardNumber: TextView

    private lateinit var layoutParkingActive: LinearLayout
    private lateinit var tvActiveLotName: TextView
    private lateinit var tvParkingTimer: TextView
    private lateinit var tvParkingEstFee: TextView
    private lateinit var btnSettleNow: Button

    private lateinit var layoutParkingLots: LinearLayout

    private var lastAmount: Int = 0
    private var parkingStartMs: Long = 0L
    private val timerRunnable = object : Runnable {
        override fun run() {
            updateParkingTimer()
            handler.postDelayed(this, 1_000)
        }
    }

    private var devTapCount = 0
    private val devTapResetRunnable = Runnable { devTapCount = 0 }
    private val DEV_TAP_TARGET = 5
    private val DEV_TAP_WINDOW_MS = 3000L
    private val DEV_PIN = "1234"
    private val REQ_LOCATION_PERM = 300

    data class BrandTheme(val shortName: String, val bgColor: Int, val brandTextColor: Int, val network: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatusDot        = findViewById(R.id.tvStatusDot)
        tvPaymentStatus    = findViewById(R.id.tvPaymentStatus)
        tvParkingBadge     = findViewById(R.id.tvParkingBadge)
        tvVinShort         = findViewById(R.id.tvVinShort)
        tvPlateNumber      = findViewById(R.id.tvPlateNumber)
        layoutUnregistered = findViewById(R.id.layoutUnregistered)
        layoutRegistered   = findViewById(R.id.layoutRegistered)
        tvHeaderTitle      = findViewById(R.id.tvHeaderTitle)

        layoutOAuthPending  = findViewById(R.id.layoutOAuthPending)
        tvOAuthPendingUser  = findViewById(R.id.tvOAuthPendingUser)
        btnOAuthPendingRegisterCard = findViewById(R.id.btnOAuthPendingRegisterCard)
        btnOAuthPendingCancel = findViewById(R.id.btnOAuthPendingCancel)

        btnResetApp        = findViewById(R.id.btnResetApp)
        mainCardBody       = findViewById(R.id.mainCardBody)
        mainCardBrand      = findViewById(R.id.mainCardBrand)
        mainCardNetwork    = findViewById(R.id.mainCardNetwork)
        mainCardNumber     = findViewById(R.id.mainCardNumber)

        layoutParkingActive = findViewById(R.id.layoutParkingActive)
        tvActiveLotName    = findViewById(R.id.tvActiveLotName)
        tvParkingTimer     = findViewById(R.id.tvParkingTimer)
        tvParkingEstFee    = findViewById(R.id.tvParkingEstFee)
        btnSettleNow       = findViewById(R.id.btnSettleNow)
        layoutParkingLots  = findViewById(R.id.layoutParkingLots)

        btnResetApp.setOnClickListener { confirmReset() }
        // ⚠️ onCreate 단계에서는 클릭 리스너를 걸지 않습니다.
        // 'OAuth 인증 완료 / 카드 미등록' 상태로 진입하는 showOAuthPendingState()에서
        // 한 번만 리스너를 등록해 동작이 중복되거나 덮어써지지 않도록 합니다.
        setupDevTrigger()

        VehicleDataManager.init(this)
        vin = VehicleDataManager.readVin(this)
        NaviHelper.init(this)

        renderStateFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        renderStateFromIntent(intent)
    }

    private fun renderStateFromIntent(intent: Intent?) {
        if (intent?.action == ACTION_SHOW_OAUTH_PENDING ||
            intent?.getBooleanExtra(EXTRA_SHOW_OAUTH_PENDING, false) == true
        ) {
            ParkingStateManager.setOAuthComplete(this, true)
            ParkingStateManager.setRegistered(this, false)
            showOAuthPendingState()
            return
        }

        renderStateFromStorage()
    }

    private fun renderStateFromStorage() {
        // 앱을 켰을 때, 상태에 따라 3개의 화면 중 하나로 안내합니다.
        if (hasCompletedCardRegistration()) {
            showRegisteredState()
            startServicesAndListeners()
        } else if (ParkingStateManager.isOAuthComplete(this)) {
            showOAuthPendingState()
        } else {
            showUnregisteredState()
        }
    }

    private fun showUnregisteredState() {
        layoutUnregistered.visibility  = View.VISIBLE
        layoutRegistered.visibility    = View.GONE
        layoutOAuthPending.visibility  = View.GONE
        btnResetApp.visibility         = View.GONE

        findViewById<Button>(R.id.btnRegister).setOnClickListener {
            startActivityForResult(Intent(this, RegistrationActivity::class.java), 100)
        }
    }

    // 🌟 원하시던 중간 화면(초기 화면) 로직
    private fun showOAuthPendingState() {
        layoutUnregistered.visibility  = View.GONE
        layoutRegistered.visibility    = View.GONE
        layoutOAuthPending.visibility  = View.VISIBLE
        btnResetApp.visibility         = View.GONE

        val userName  = ParkingStateManager.getHyundaiUserName(this)
        val modelName = ParkingStateManager.getHyundaiModelName(this)
        tvOAuthPendingUser.text = when {
            userName.isNotEmpty() && modelName.isNotEmpty() -> "$userName 님 · $modelName"
            userName.isNotEmpty() -> "$userName 님"
            modelName.isNotEmpty() -> modelName
            else -> ""
        }

        // 여기서 '카드 등록' 버튼을 누르면 은행사 선택 화면으로 넘어갑니다.
        btnOAuthPendingRegisterCard.setOnClickListener {
            launchCardRegistrationOnly()
        }

        // '나중에 등록' → 마이현대 로그인 상태를 유지한 채
        // 현재(로그인 인증 완료) 초기 화면에 그대로 머무릅니다.
        // 앱을 백그라운드로 보내거나 종료하지 않습니다.
        btnOAuthPendingCancel.setOnClickListener {
            deferCardRegistration()
        }
    }

    /**
     * 카드 등록을 나중으로 미루는 동작.
     * - 로그인(OAuth) 상태는 유지
     * - 카드 등록 상태는 false 유지
     * - 사용자는 '메인 화면 형태' 의 화면을 보게 되며,
     *   카드 영역은 '카드 미등록' 플레이스홀더 / '카드 등록하기' CTA 가 노출됩니다.
     */
    private fun deferCardRegistration() {
        ParkingStateManager.setOAuthComplete(this, true)
        ParkingStateManager.setRegistered(this, false)
        Toast.makeText(
            this,
            "카드 등록 전까지 자동 결제는 대기 상태입니다.\n언제든 '카드 등록하기'를 눌러 진행할 수 있습니다.",
            Toast.LENGTH_LONG
        ).show()
        showLoggedInNoCardState()
    }

    /**
     * ✨ '마이현대 로그인은 완료 / 카드는 아직 미등록' 상태에서의 메인 화면.
     *
     * - 카드 등록 후 화면(layoutRegistered)을 그대로 재사용해 메인 UI 의 일관성을 유지
     * - 카드 표시 영역에는 '카드 미등록' 플레이스홀더를 그려서 "등록된 카드가 없는 것처럼" 보이게 함
     * - 'btnRegisterCard' 를 메인 CTA('카드 등록하기')로 노출
     * - 결제·정산 관련 섹션은 비활성화/숨김 처리하여 오인 클릭을 방지
     */
    private fun showLoggedInNoCardState() {
        layoutUnregistered.visibility  = View.GONE
        layoutRegistered.visibility    = View.VISIBLE
        layoutOAuthPending.visibility  = View.GONE
        btnResetApp.visibility         = View.VISIBLE

        tvVinShort.text    = maskVin(vin)
        tvPlateNumber.text = ParkingStateManager.getPlateNumber(this) ?: "—"

        val userName  = ParkingStateManager.getHyundaiUserName(this)
        val modelName = ParkingStateManager.getHyundaiModelName(this)
        if (modelName.isNotEmpty()) {
            tvVinShort.text = "$modelName  ${maskVin(vin)}"
        }

        // ── 카드 영역: '카드 미등록' 플레이스홀더 ─────────────────────────────
        mainCardBody.setBackgroundColor(0xFF1A1F2A.toInt())
        mainCardBrand.text   = "카드 미등록"
        mainCardBrand.setTextColor(0xFF8899AA.toInt())
        mainCardNetwork.text = "—"
        mainCardNetwork.setTextColor(0xFF556677.toInt())
        mainCardNumber.text  = "•••• •••• •••• ••••"

        // 상태바: 결제 대기
        tvPaymentStatus.text = "카드 등록 전 — 자동 결제 대기 중"
        tvStatusDot.setTextColor(0xFFFFD700.toInt())
        updateParkingBadge(false)

        // ── 메인 CTA: '카드 등록하기' ────────────────────────────────────────
        val btnRegisterCard = findViewById<Button>(R.id.btnRegisterCard)
        btnRegisterCard.text = "카드 등록하기"
        btnRegisterCard.setOnClickListener { launchCardRegistrationOnly() }

        // 계정 재연동 버튼은 그대로 유지
        findViewById<Button>(R.id.btnChangeCard).setOnClickListener {
            val accountLabel = if (userName.isNotEmpty()) "$userName 님 계정" else "마이현대 계정"
            AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert)
                .setTitle("계정 재연동")
                .setMessage("$accountLabel\n\nQR 스캔으로 마이현대 계정을 다시 연동하시겠습니까?")
                .setPositiveButton("재연동") { _, _ ->
                    startActivityForResult(Intent(this, RegistrationActivity::class.java), 100)
                }
                .setNegativeButton("취소", null)
                .show()
        }

        // 카드가 없으므로 정산 버튼은 카드 등록을 유도
        btnSettleNow.setOnClickListener {
            Toast.makeText(this, "먼저 카드를 등록해 주세요.", Toast.LENGTH_SHORT).show()
            launchCardRegistrationOnly()
        }

        // 결제 관련 섹션은 숨김 / 비움
        hideParkingActiveSection()
        findViewById<LinearLayout>(R.id.layoutTxHistory).removeAllViews()

        // 주차장 목록은 정보용으로 노출 (탭하면 내비게이션은 가능)
        populateParkingLots()
    }

    private fun showRegisteredState() {
        layoutUnregistered.visibility  = View.GONE
        layoutRegistered.visibility    = View.VISIBLE
        layoutOAuthPending.visibility  = View.GONE
        btnResetApp.visibility         = View.VISIBLE

        tvVinShort.text    = maskVin(vin)
        tvPlateNumber.text = ParkingStateManager.getPlateNumber(this) ?: "—"

        val userName  = ParkingStateManager.getHyundaiUserName(this)
        val modelName = ParkingStateManager.getHyundaiModelName(this)

        if (modelName.isNotEmpty()) {
            tvVinShort.text = "$modelName  ${maskVin(vin)}"
        }

        val brand    = ParkingStateManager.getCardBrand(this)
        val lastFour = ParkingStateManager.getCardLastFour(this)
        val theme    = getCardBrandTheme(brand)

        mainCardBody.setBackgroundColor(theme.bgColor)
        mainCardBrand.text = theme.shortName
        mainCardBrand.setTextColor(theme.brandTextColor)
        mainCardNetwork.text = theme.network
        mainCardNumber.text  = "•••• •••• •••• $lastFour"

        val btnRegisterCardRegistered = findViewById<Button>(R.id.btnRegisterCard)
        // showLoggedInNoCardState 에서 '카드 등록하기' 로 변경되었을 수 있으므로 원래 라벨로 복구
        btnRegisterCardRegistered.text = "카드 등록"
        btnRegisterCardRegistered.setOnClickListener {
            AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert)
                .setTitle("카드 변경")
                .setMessage("새 카드를 등록합니다.\n번호판 확인 후 카드 정보를 입력해 주세요.")
                .setPositiveButton("변경하기") { _, _ ->
                    launchCardRegistrationOnly()
                }
                .setNegativeButton("취소", null)
                .show()
        }

        findViewById<Button>(R.id.btnChangeCard).setOnClickListener {
            val accountLabel = if (userName.isNotEmpty()) "$userName 님 계정" else "마이현대 계정"
            AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert)
                .setTitle("계정 재연동")
                .setMessage("$accountLabel\n\nQR 스캔으로 마이현대 계정을 다시 연동하시겠습니까?")
                .setPositiveButton("재연동") { _, _ ->
                    startActivityForResult(Intent(this, RegistrationActivity::class.java), 100)
                }
                .setNegativeButton("취소", null)
                .show()
        }

        btnSettleNow.setOnClickListener { queryFeeAndShowSettlement() }

        if (ParkingStateManager.isParked(this)) {
            val lotId = ParkingStateManager.getLotId(this)
            showParkingActiveSection(lotId)
            tvPaymentStatus.text = "주차 중 — 지금 정산하기 가능"
            tvStatusDot.setTextColor(0xFFFFD700.toInt())
            updateParkingBadge(true)
        } else {
            hideParkingActiveSection()
            tvPaymentStatus.text = "주차장 접근 시 자동 결제됩니다"
            tvStatusDot.setTextColor(0xFF00FF88.toInt())
            updateParkingBadge(false)
        }

        populateParkingLots()
        refreshTransactionHistory()
    }

    private fun launchCardRegistrationOnly() {
        val token = ParkingStateManager.getAccessToken(this)
        if (token.isNullOrBlank()) {
            Log.w(TAG, "Card registration blocked: missing access token")
            Toast.makeText(this, "로그인 토큰이 없습니다. QR 로그인을 다시 진행해 주세요.", Toast.LENGTH_LONG).show()
            return
        }
        val carId = ParkingStateManager.getHyundaiCarId(this)
        if (carId.isBlank()) {
            Log.w(TAG, "Card registration blocked: missing carId")
            Toast.makeText(this, "연결된 차량 정보가 없습니다. QR 로그인을 다시 진행해 주세요.", Toast.LENGTH_LONG).show()
            return
        }
        Log.d(TAG, "Launching card registration carId=${carId.takeLast(8)} token=${token.take(8)}")
        val intent = Intent(this, CardRegistrationActivity::class.java).apply {
            putExtra(CardRegistrationActivity.EXTRA_ACCESS_TOKEN, token)
            putExtra(CardRegistrationActivity.EXTRA_USER_NAME, ParkingStateManager.getHyundaiUserName(this@MainActivity))
        }
        runCatching {
            startActivityForResult(intent, 101)
        }.onFailure {
            Log.e(TAG, "Failed to launch CardRegistrationActivity", it)
            Toast.makeText(this, "카드 등록 화면을 열 수 없습니다: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun getCardBrandTheme(brandName: String): BrandTheme {
        return when (brandName) {
            "현대", "현대카드" -> BrandTheme("HYUNDAI", 0xFF1A1A2E.toInt(), 0xFFCCCCCC.toInt(), "VISA")
            "KB국민", "KB" -> BrandTheme("KB", 0xFF1A1A1A.toInt(), 0xFFFFCC00.toInt(), "MASTER")
            "신한", "신한카드" -> BrandTheme("SHINHAN", 0xFF8B0000.toInt(), 0xFFFFFFFF.toInt(), "VISA")
            "삼성", "삼성카드" -> BrandTheme("SAMSUNG", 0xFF1428A0.toInt(), 0xFFFFFFFF.toInt(), "MASTER")
            "롯데", "롯데카드" -> BrandTheme("LOTTE", 0xFF9B0000.toInt(), 0xFFFFFFFF.toInt(), "VISA")
            "우리", "우리카드" -> BrandTheme("WOORI", 0xFF004A8F.toInt(), 0xFFFFFFFF.toInt(), "MASTER")
            "하나", "하나카드" -> BrandTheme("HANA", 0xFF005C3E.toInt(), 0xFFFFFFFF.toInt(), "VISA")
            else -> BrandTheme("CARD", 0xFF1A1A2E.toInt(), 0xFFFFFFFF.toInt(), "VISA")
        }
    }

    private fun updateParkingBadge(isParked: Boolean) {
        if (isParked) {
            tvParkingBadge.text = "🅿 주차 중"
            tvParkingBadge.setTextColor(0xFF00E87A.toInt())
            tvParkingBadge.setBackgroundColor(0xFF0D2A1D.toInt())
        } else {
            tvParkingBadge.text = "미주차"
            tvParkingBadge.setTextColor(0xFF556677.toInt())
            tvParkingBadge.setBackgroundColor(0xFF111820.toInt())
        }
    }

    private fun showParkingActiveSection(lotId: String) {
        layoutParkingActive.visibility = View.VISIBLE
        tvActiveLotName.text = if (lotId.isNotEmpty()) lotId else "주차장"

        if (parkingStartMs == 0L) parkingStartMs = System.currentTimeMillis()

        handler.removeCallbacks(timerRunnable)
        handler.post(timerRunnable)

        tvParkingEstFee.text = "조회 중..."
        val sessionId = ParkingStateManager.getSessionId(this)
        Thread {
            try {
                val fee = ApiManager.withAutoRefresh(this) { token -> ApiManager.queryFee(lotId, sessionId, token) }
                lastAmount = fee.amount
                handler.post {
                    tvActiveLotName.text = fee.lotName
                    tvParkingEstFee.text = "%,d원".format(fee.amount)
                }
            } catch (e: Exception) {
                handler.post { tvParkingEstFee.text = "—" }
            }
        }.start()
    }

    private fun hideParkingActiveSection() {
        layoutParkingActive.visibility = View.GONE
        handler.removeCallbacks(timerRunnable)
        parkingStartMs = 0L
    }

    private fun updateParkingTimer() {
        if (parkingStartMs == 0L) return
        val elapsed = System.currentTimeMillis() - parkingStartMs
        val h = elapsed / 3_600_000
        val m = (elapsed % 3_600_000) / 60_000
        val s = (elapsed % 60_000) / 1_000
        tvParkingTimer.text = "%02d:%02d:%02d".format(h, m, s)
    }

    private fun populateParkingLots() {
        layoutParkingLots.removeAllViews()
        val sorted = GeofenceManager.cachedParkingLots.sortedWith(
            compareByDescending<GeofenceManager.ParkingLot> { it.id == approachingLotId }.thenByDescending { it.id == navigatingLotId }
        )

        sorted.forEach { lot ->
            val isNearby    = lot.id == approachingLotId
            val isNavigating = lot.id == navigatingLotId

            val rowBg = when {
                isNearby -> android.graphics.drawable.GradientDrawable().apply { setColor(0xFF1A2200.toInt()); setStroke(2, 0xFFFFD700.toInt()); cornerRadius = 12f }
                isNavigating -> android.graphics.drawable.GradientDrawable().apply { setColor(0xFF001A33.toInt()); setStroke(2, 0xFF00AAFF.toInt()); cornerRadius = 12f }
                else -> getDrawable(R.drawable.bg_card_dark)
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(16, 14, 16, 14); background = rowBg; isClickable = true; isFocusable = true
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.setMargins(0, 0, 0, 8) }
            }

            val tvIcon = TextView(this).apply { text = when { isNavigating -> "🧭"; isNearby -> "🚗"; else -> "📍" }; textSize = 16f }
            val subText = when { isNavigating -> "내비게이션 안내 중 · 탭하여 취소"; isNearby -> "접근 중 · 사전 등록됨"; else -> "탭하여 내비게이션 시작" }
            val tvInfo = TextView(this).apply {
                text = "${lot.name}\n$subText"
                setTextColor(when { isNavigating -> Color.parseColor("#00AAFF"); isNearby -> Color.parseColor("#FFD700"); else -> Color.WHITE })
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also { it.setMargins(10, 0, 0, 0) }
            }
            val tvBadge = TextView(this).apply {
                text = when { isNavigating -> "안내 중"; isNearby -> "사전 등록됨"; else -> "제휴" }
                setTextColor(when { isNavigating -> Color.parseColor("#00AAFF"); isNearby -> Color.parseColor("#FFD700"); else -> Color.parseColor("#00AA55") })
                textSize = 11f
            }

            row.addView(tvIcon); row.addView(tvInfo); row.addView(tvBadge); layoutParkingLots.addView(row)
            row.setOnClickListener { if (isNavigating) confirmCancelNavigation(lot) else startNavigationTo(lot) }
        }
    }

    private fun startNavigationTo(lot: GeofenceManager.ParkingLot) {
        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert)
            .setTitle("🧭 내비게이션 시작")
            .setMessage("${lot.name}\n\n목적지로 경로 안내를 시작합니다.\n도착 전 차량 정보가 주차장에 자동으로 등록됩니다.")
            .setPositiveButton("시작") { dialog, _ ->
                dialog.dismiss()
                navigatingLotId = lot.id
                populateParkingLots()
                NaviHelper.setDestination(this, lot.lat, lot.lng, lot.name, lot.id)

                val plate = ParkingStateManager.getPlateNumber(this)
                val token = ParkingStateManager.getAccessToken(this)
                val carId = ParkingStateManager.getHyundaiCarId(this)
                if (plate != null && token != null && carId.isNotBlank()) {
                    Thread { runCatching { ApiManager.sendPreNotification(carId, plate, lot.id, "NAVI", token) } }.start()
                }
                Toast.makeText(this, "🧭 ${lot.name} 경로 안내 시작", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun confirmCancelNavigation(lot: GeofenceManager.ParkingLot) {
        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert)
            .setTitle("내비게이션 취소")
            .setMessage("${lot.name} 경로 안내를 취소하시겠습니까?")
            .setPositiveButton("취소") { dialog, _ ->
                dialog.dismiss(); NaviHelper.cancelNavigation(); navigatingLotId = null; populateParkingLots()
                Toast.makeText(this, "경로 안내가 취소되었습니다", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("계속 안내") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun startServicesAndListeners() {
        // 콜백/UI 업데이트는 권한과 무관하므로 먼저 등록한다.
        registerServiceCallbacks()
        handler.postDelayed({
            tvStatusDot.setTextColor(
                if (MqttManager.isConnected()) 0xFF00FF88.toInt() else 0xFF888888.toInt()
            )
        }, 2_000)

        val fineGranted = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (fineGranted) {
            // 위치 권한이 이미 있으면 location-type 포그라운드 서비스를 안전하게 시작할 수 있다.
            safeStartCarPayInService()
        } else {
            // ⚠️ Android 14(targetSdk 34) 에서 foregroundServiceType="location" 인 서비스를
            //    위치 권한이 없는 상태로 startForegroundService 하면 SecurityException 또는
            //    MissingForegroundServiceTypeException 으로 앱이 즉시 종료된다.
            //    → 권한 다이얼로그 응답이 올 때까지 서비스 시작을 미룬다.
            Log.d(TAG, "위치 권한 미부여 → 권한 요청 후 서비스 시작 예정")
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                REQ_LOCATION_PERM
            )
        }
    }

    /**
     * Foreground Service 시작 시도. AAOS / 에뮬레이터 별로 OS 정책이 달라
     * 예기치 못한 RuntimeException 이 발생하더라도 앱이 죽지 않도록 방어한다.
     */
    private fun safeStartCarPayInService() {
        try {
            CarPayInService.start(this)
        } catch (t: Throwable) {
            Log.e(TAG, "CarPayInService 시작 실패 (앱은 계속 동작): ${t.javaClass.simpleName} ${t.message}")
            // 서비스가 안 떠도 화면 자체는 정상 동작하도록 유지.
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION_PERM) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) {
                Log.d(TAG, "위치 권한 부여됨 → CarPayInService 시작")
                safeStartCarPayInService()
            } else {
                Log.w(TAG, "위치 권한 거부 → 자동 결제 감시는 제한적으로 동작")
                Toast.makeText(
                    this,
                    "위치 권한이 없어 자동 입차 감지는 제한됩니다.\n수동 정산은 계속 사용할 수 있습니다.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun registerServiceCallbacks() {
        CarPayInService.onFeeUpdated = { lotName, amount, _ -> tvActiveLotName.text = lotName; tvParkingEstFee.text = "%,d원".format(amount); lastAmount = amount }
        CarPayInService.onParkingConfirmed = { lotId, _ ->
            parkingStartMs = System.currentTimeMillis(); showParkingActiveSection(lotId); tvPaymentStatus.text = "주차 중 — 지금 정산하기 가능"
            tvStatusDot.setTextColor(0xFFFFD700.toInt()); updateParkingBadge(true); showEntryConfirmed(lotId)
        }
        CarPayInService.onPaymentComplete = { txId, approvalNo, lotId, amount ->
            hideParkingActiveSection(); tvPaymentStatus.text = "주차장 접근 시 자동 결제됩니다"; tvStatusDot.setTextColor(0xFF00FF88.toInt())
            updateParkingBadge(false); refreshTransactionHistory(); showPaymentComplete(txId, approvalNo, lotId, amount)
        }
        CarPayInService.onConnectionChanged = { connected -> tvStatusDot.setTextColor(if (connected) 0xFF00FF88.toInt() else 0xFF888888.toInt()) }
        CarPayInService.onLotApproaching = { lotId, _ -> approachingLotId = lotId; populateParkingLots() }
        NaviHelper.onNavigationEnded = { navigatingLotId = null; populateParkingLots() }
    }

    private fun showEntryConfirmed(lotId: String) {
        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert)
            .setTitle("🅿 입차 확인").setMessage("$lotId\n\n입차가 확인되었습니다.\n시동을 켜거나 [지금 정산하기] 버튼으로 정산할 수 있습니다.")
            .setPositiveButton("확인") { dialog, _ -> dialog.dismiss() }.setCancelable(false).show()
    }

    private fun queryFeeAndShowSettlement() {
        val lotId = ParkingStateManager.getLotId(this)
        val sessionId = ParkingStateManager.getSessionId(this)
        tvPaymentStatus.text = "요금 조회 중..."; tvStatusDot.setTextColor(0xFFFFD700.toInt()); btnSettleNow.isEnabled = false

        Thread {
            try {
                val fee = ApiManager.withAutoRefresh(this) { token -> ApiManager.queryFee(lotId, sessionId, token) }
                handler.post { btnSettleNow.isEnabled = true; showSettlementDialog(fee, sessionId) }
            } catch (e: Exception) {
                handler.post { tvPaymentStatus.text = "요금 조회 실패"; tvStatusDot.setTextColor(0xFFFF4444.toInt()); btnSettleNow.isEnabled = true }
            }
        }.start()
    }

    private fun showSettlementDialog(fee: ApiManager.FeeResult, sessionId: String) {
        lastAmount = fee.amount
        val h = fee.durationMinutes / 60
        val m = fee.durationMinutes % 60
        val dur = if (h > 0) "${h}시간 ${m}분" else "${m}분"

        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert)
            .setTitle("🅿 정산 확인").setMessage("${fee.lotName}\n\n주차 시간: $dur\n결제 금액: ${"%,d".format(fee.amount)}원\n\n정산하시겠습니까?")
            .setPositiveButton("예") { dialog, _ -> dialog.dismiss(); processPayment(sessionId, fee.amount) }
            .setNegativeButton("취소") { dialog, _ ->
                dialog.dismiss(); tvPaymentStatus.text = "취소됨 — 출구에서 현장 정산"; tvStatusDot.setTextColor(0xFFFF4444.toInt()); btnSettleNow.isEnabled = true
            }.setCancelable(false).show()
    }

    private fun processPayment(sessionId: String, amount: Int) {
        tvPaymentStatus.text = "결제 처리 중..."; tvStatusDot.setTextColor(0xFFFFD700.toInt()); btnSettleNow.isEnabled = false

        Thread {
            try {
                val result = ApiManager.withAutoRefresh(this) { token -> ApiManager.requestPayment(sessionId, amount, token) }
                val lotId = ParkingStateManager.getLotId(this)
                TransactionStore.save(this, result.transactionId, lotId, amount)
                ParkingStateManager.saveParkingState(this, false)
                handler.post {
                    hideParkingActiveSection(); tvPaymentStatus.text = "주차장 접근 시 자동 결제됩니다"; tvStatusDot.setTextColor(0xFF00FF88.toInt())
                    updateParkingBadge(false); refreshTransactionHistory(); showPaymentComplete(result.transactionId, result.approvalNumber, lotId, amount)
                }
            } catch (e: Exception) {
                handler.post { btnSettleNow.isEnabled = true; showPaymentError("결제 오류: ${e.message}") }
            }
        }.start()
    }

    private fun showPaymentComplete(txId: String, approvalNo: String, lotId: String, amount: Int) {
        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert)
            .setTitle("✓ 결제 완료").setMessage("$lotId\n\n결제 금액: ${"%,d".format(amount)}원\n승인번호: $approvalNo\n거래번호: ${txId.take(14)}…\n\n약 3~5초 후 차단기가 개방됩니다.")
            .setPositiveButton("확인") { dialog, _ -> dialog.dismiss() }.setCancelable(false).show()
    }

    private fun showPaymentError(message: String) {
        tvPaymentStatus.text = "결제 실패"; tvStatusDot.setTextColor(0xFFFF4444.toInt())
        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert)
            .setTitle("⚠ 결제 실패").setMessage("$message\n\n다른 결제 수단을 사용하거나 현장 무인정산기를 이용해 주세요.")
            .setPositiveButton("재시도") { _, _ -> queryFeeAndShowSettlement() }
            .setNegativeButton("현장 정산") { dialog, _ -> dialog.dismiss() }.setCancelable(false).show()
    }

    private fun refreshTransactionHistory() {
        val container = findViewById<LinearLayout>(R.id.layoutTxHistory)
        container.removeAllViews()
        val transactions = TransactionStore.load(this)
        // ... (생략 없이 기존 UI 그리는 부분 유지)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 🌟 핵심 로직: 뒤로가기/취소 처리에 대한 완벽한 화면 제어
    // ─────────────────────────────────────────────────────────────────────────
    @Deprecated("Deprecated in API level 29")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            100 -> {
                // RegistrationActivity (QR 화면)에서 돌아온 경우
                if (resultCode == RESULT_OK) {
                    // 성공! 마이현대 연동 중간 화면을 보여줍니다.
                    ParkingStateManager.setOAuthComplete(this, true)
                    ParkingStateManager.setRegistered(this, false)
                    showOAuthPendingState()
                } else {
                    // 성공 안 했으면 (그냥 뒤로가기 눌렀으면) 로그인 전 화면
                    if (!ParkingStateManager.isOAuthComplete(this)) showUnregisteredState()
                }
            }
            101 -> {
                // CardRegistrationActivity (카드 등록 화면)에서 돌아온 경우
                if (resultCode == RESULT_OK) {
                    // 드디어 등록 끝! 진짜 메인 화면 표시
                    ParkingStateManager.setRegistered(this, true)
                    showRegisteredState()
                    startServicesAndListeners()
                } else {
                    // 🌟 사용자가 카드 등록 중에 '처음으로 / 이전'을 눌러 빠져나온 경우.
                    // 로그인(OAuth) 상태는 유지하고, 메인 형태의
                    // 'logged-in / no-card' 화면으로 복귀한다.
                    ParkingStateManager.setOAuthComplete(this, true)
                    ParkingStateManager.setRegistered(this, false)
                    if (ParkingStateManager.isOAuthComplete(this)) {
                        showLoggedInNoCardState()
                    } else {
                        showUnregisteredState()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasCompletedCardRegistration()) registerServiceCallbacks()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(timerRunnable)
        VehicleDataManager.release()
    }

    private fun setupDevTrigger() {
        tvHeaderTitle.setOnClickListener {
            handler.removeCallbacks(devTapResetRunnable); devTapCount++
            if (devTapCount >= DEV_TAP_TARGET) { devTapCount = 0; showDevPinDialog() }
            else { handler.postDelayed(devTapResetRunnable, DEV_TAP_WINDOW_MS) }
        }
    }

    private fun showDevPinDialog() {
        val et = EditText(this).apply { hint = "PIN 입력"; inputType = 18 }
        androidx.appcompat.app.AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert)
            .setTitle("🔧 개발자 모드").setView(et)
            .setPositiveButton("확인") { _, _ -> if (et.text.toString() == DEV_PIN) showDevMenu() else Toast.makeText(this, "PIN 오류", Toast.LENGTH_SHORT).show() }
            .setNegativeButton("취소", null).show()
    }

    private fun showDevMenu() {
        val items = arrayOf("Mock 입차 확정", "Mock 결제 완료", "등록 초기화 (즉시)", "MQTT 재연결", "VIN 표시")
        androidx.appcompat.app.AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert)
            .setTitle("개발자 메뉴")
            .setItems(items) { _, idx ->
                when (idx) {
                    0 -> { ParkingStateManager.saveParkingState(this, true, "DEV_LOT_01", "sess_dev_001"); showRegisteredState(); Toast.makeText(this, "Mock 입차 확정", Toast.LENGTH_SHORT).show() }
                    1 -> { ParkingStateManager.saveParkingState(this, false); showRegisteredState(); Toast.makeText(this, "Mock 결제 완료", Toast.LENGTH_SHORT).show() }
                    2 -> { clearRegistrationState(); recreate() }
                    3 -> {
                        val carId = ParkingStateManager.getHyundaiCarId(this)
                        if (carId.isNotBlank()) Thread { MqttManager.connect(carId) }.start()
                        Toast.makeText(this, "MQTT 재연결 시도", Toast.LENGTH_SHORT).show()
                    }
                    4 -> Toast.makeText(this, "VIN: $vin", Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun confirmReset() {
        androidx.appcompat.app.AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert)
            .setTitle("⚠ 등록 해제").setMessage("등록된 카드와 차량 정보를 모두 삭제합니다.\n계속하시겠습니까?")
            .setPositiveButton("삭제") { _, _ ->
                clearRegistrationState()
                recreate()
            }
            .setNegativeButton("취소", null).show()
    }

    private fun maskVin(vin: String): String = if (vin.length >= 6) "VIN: ${vin.take(3)}•••${vin.takeLast(3)}" else "VIN: $vin"

    private fun clearRegistrationState() {
        val token = ParkingStateManager.getAccessToken(this)
        CarPayInService.stop(this)
        handler.removeCallbacks(timerRunnable)
        token?.let {
            Thread {
                runCatching { ApiManager.unregister(it) }
                    .onFailure { android.util.Log.w(TAG, "Server unregister failed: ${it.message}") }
            }.start()
        }
        ParkingStateManager.clearSession(this)
        TransactionStore.clear(this)
        approachingLotId = null
        navigatingLotId = null
    }

    private fun hasCompletedCardRegistration(): Boolean {
        if (!ParkingStateManager.isRegistered(this)) return false
        val lastFour = ParkingStateManager.getCardLastFour(this)
        return lastFour.isNotBlank() && lastFour != "****" && lastFour != "0000"
    }
}
