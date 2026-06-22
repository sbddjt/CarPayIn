package com.example.carpayin.ui

import com.example.carpayin.R
import android.content.Intent
import android.location.Location
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.graphics.Rect
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.carpayin.data.ParkingStateManager
import com.example.carpayin.data.TransactionStore
import com.example.carpayin.network.ApiManager
import com.example.carpayin.network.MqttManager
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
        const val ACTION_SHOW_DEV_MENU = "com.example.carpayin.SHOW_DEV_MENU"
        const val EXTRA_SHOW_DEV_MENU = "extra_show_dev_menu"
    }

    private var vin: String = ""

    private lateinit var tvStatusDot: TextView
    private lateinit var tvPaymentStatus: TextView
    private lateinit var tvParkingBadge: TextView
    private lateinit var tvVinShort: TextView
    private lateinit var tvPlateNumber: TextView
    private lateinit var layoutUnregistered: LinearLayout
    private lateinit var layoutRegistered: ScrollView
    private lateinit var tvHeaderTitle: RelativeLayout
    private lateinit var mainHeaderLogoTapArea: View
    private lateinit var tvFeatureHint: TextView
    private lateinit var sectionCard: LinearLayout
    private lateinit var sectionVehicle: LinearLayout
    private lateinit var sectionParking: LinearLayout
    private lateinit var sectionHistory: LinearLayout
    private lateinit var btnFeatureCard: Button
    private lateinit var btnFeatureVehicle: Button
    private lateinit var btnFeatureParking: Button
    private lateinit var btnFeatureHistory: Button

    // 🌟 원하시던 중간 화면 UI
    private lateinit var layoutOAuthPending: LinearLayout
    private lateinit var tvOAuthPendingUser: TextView
    private lateinit var btnOAuthPendingRegisterCard: Button
    private lateinit var btnOAuthPendingCancel: Button

    private lateinit var btnDevMenuHidden: Button
    private lateinit var btnResetApp: Button
    private lateinit var mainCardBody: LinearLayout
    private lateinit var mainCardBrand: TextView
    private lateinit var mainCardNetwork: TextView
    private lateinit var mainCardNumber: TextView
    private lateinit var layoutVehicleInfo: LinearLayout

    private lateinit var layoutParkingActive: LinearLayout
    private lateinit var tvActiveLotName: TextView
    private lateinit var tvParkingTimer: TextView
    private lateinit var tvParkingEstFee: TextView
    private lateinit var btnSettleNow: Button

    private lateinit var layoutParkingLots: LinearLayout

    private var lastAmount: Int = 0
    private var parkingStartMs: Long = 0L
    private var isDevMenuVisible = false
    private val timerRunnable = object : Runnable {
        override fun run() {
            updateParkingTimer()
            handler.postDelayed(this, 1_000)
        }
    }



    data class BrandTheme(val shortName: String, val bgColor: Int, val brandTextColor: Int, val network: String)
    private enum class RegisteredFeature { CARD, VEHICLE, PARKING, HISTORY }

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
        mainHeaderLogoTapArea = findViewById(R.id.mainHeaderLogoTapArea)
        tvFeatureHint      = findViewById(R.id.tvFeatureHint)
        sectionCard        = findViewById(R.id.sectionCard)
        sectionVehicle     = findViewById(R.id.sectionVehicle)
        sectionParking     = findViewById(R.id.sectionParking)
        sectionHistory     = findViewById(R.id.sectionHistory)
        btnFeatureCard     = findViewById(R.id.btnFeatureCard)
        btnFeatureVehicle  = findViewById(R.id.btnFeatureVehicle)
        btnFeatureParking  = findViewById(R.id.btnFeatureParking)
        btnFeatureHistory  = findViewById(R.id.btnFeatureHistory)

        layoutOAuthPending  = findViewById(R.id.layoutOAuthPending)
        tvOAuthPendingUser  = findViewById(R.id.tvOAuthPendingUser)
        btnOAuthPendingRegisterCard = findViewById(R.id.btnOAuthPendingRegisterCard)
        btnOAuthPendingCancel = findViewById(R.id.btnOAuthPendingCancel)

        btnDevMenuHidden   = findViewById(R.id.btnDevMenuHidden)
        btnResetApp        = findViewById(R.id.btnResetApp)
        mainCardBody       = findViewById(R.id.mainCardBody)
        mainCardBrand      = findViewById(R.id.mainCardBrand)
        mainCardNetwork    = findViewById(R.id.mainCardNetwork)
        mainCardNumber     = findViewById(R.id.mainCardNumber)
        layoutVehicleInfo  = findViewById(R.id.layoutVehicleInfo)

        layoutParkingActive = findViewById(R.id.layoutParkingActive)
        tvActiveLotName    = findViewById(R.id.tvActiveLotName)
        tvParkingTimer     = findViewById(R.id.tvParkingTimer)
        tvParkingEstFee    = findViewById(R.id.tvParkingEstFee)
        btnSettleNow       = findViewById(R.id.btnSettleNow)
        layoutParkingLots  = findViewById(R.id.layoutParkingLots)

        btnResetApp.setOnClickListener { confirmReset() }
        setupRegisteredFeatureMenu()
        // ⚠️ onCreate 단계에서는 클릭 리스너를 걸지 않습니다.
        // 'OAuth 인증 완료 / 카드 미등록' 상태로 진입하는 showOAuthPendingState()에서
        // 한 번만 리스너를 등록해 동작이 중복되거나 덮어써지지 않도록 합니다.
        setupDevTrigger()

        // prefs에서 VIN 즉시 읽기 (Car API 연결 전, 논블로킹)
        vin = VehicleDataManager.readVin(this)

        // 백그라운드: Pleos 패널 소유권 + Car API 바인딩
        // takePanelControl()은 Pleos IPC 호출 → 메인 스레드에서 실행 시 500ms+ 블로킹
        NaviHelper.onNavigationEnded = {
            handler.post {
                Thread { NaviHelper.reacquirePanelControl(applicationContext) }.start()
            }
        }


        val appContext = applicationContext
        Thread {
            NaviHelper.takePanelControl(appContext)
            NaviHelper.init(appContext)
            VehicleDataManager.init(appContext)
            val realVin = VehicleDataManager.readVin(appContext)
            if (realVin.isNotBlank() && realVin != vin) {
                handler.post { vin = realVin }
            }
        }.start()

        renderStateFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        renderStateFromIntent(intent)
    }

    private fun renderStateFromIntent(intent: Intent?) {
        if (intent?.action == ACTION_SHOW_DEV_MENU ||
            intent?.getBooleanExtra(EXTRA_SHOW_DEV_MENU, false) == true
        ) {
            renderStateFromStorage()
            handler.post { showDevMenu() }
            return
        }

        if (intent?.action == ACTION_SHOW_OAUTH_PENDING ||
            intent?.getBooleanExtra(EXTRA_SHOW_OAUTH_PENDING, false) == true
        ) {
            if (!hasOAuthSession()) {
                setIntent(Intent(this, MainActivity::class.java))
                renderStateFromStorage()
                return
            }
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
            if (hasOAuthSession()) {
                showOAuthPendingState()
            } else {
                ParkingStateManager.clearSession(this)
                showUnregisteredState()
            }
        } else {
            showUnregisteredState()
        }
    }

    private fun setupRegisteredFeatureMenu() {
        btnFeatureCard.setOnClickListener { showRegisteredFeature(RegisteredFeature.CARD) }
        btnFeatureVehicle.setOnClickListener { showRegisteredFeature(RegisteredFeature.VEHICLE) }
        btnFeatureParking.setOnClickListener { showRegisteredFeature(RegisteredFeature.PARKING) }
        btnFeatureHistory.setOnClickListener { showRegisteredFeature(RegisteredFeature.HISTORY) }

        mainCardBody.setOnClickListener { showCardInfoDialog() }
        layoutVehicleInfo.setOnClickListener { showVehicleInfoDialog() }
        layoutParkingActive.setOnClickListener { showParkingInfoDialog() }
    }

    private fun showRegisteredFeature(feature: RegisteredFeature?) {
        sectionCard.visibility = if (feature == RegisteredFeature.CARD) View.VISIBLE else View.GONE
        sectionVehicle.visibility = if (feature == RegisteredFeature.VEHICLE) View.VISIBLE else View.GONE
        sectionParking.visibility = if (feature == RegisteredFeature.PARKING) View.VISIBLE else View.GONE
        sectionHistory.visibility = if (feature == RegisteredFeature.HISTORY) View.VISIBLE else View.GONE

        updateFeatureButton(btnFeatureCard, feature == RegisteredFeature.CARD)
        updateFeatureButton(btnFeatureVehicle, feature == RegisteredFeature.VEHICLE)
        updateFeatureButton(btnFeatureParking, feature == RegisteredFeature.PARKING)
        updateFeatureButton(btnFeatureHistory, feature == RegisteredFeature.HISTORY)

        tvFeatureHint.text = when (feature) {
            RegisteredFeature.CARD -> "등록된 결제 수단을 확인합니다"
            RegisteredFeature.VEHICLE -> "연동된 차량 정보를 확인합니다"
            RegisteredFeature.PARKING -> "근처 제휴 주차장과 주차 상태를 확인합니다"
            RegisteredFeature.HISTORY -> "최근 결제 내역을 확인합니다"
            null -> "확인할 기능을 선택해 주세요"
        }

        when (feature) {
            RegisteredFeature.PARKING -> populateParkingLots()
            RegisteredFeature.HISTORY -> refreshTransactionHistory()
            else -> Unit
        }
    }

    private fun updateFeatureButton(button: Button, selected: Boolean) {
        button.background = ContextCompat.getDrawable(
            this,
            if (selected) R.drawable.bg_button_primary else R.drawable.bg_button_secondary
        )
        button.setTextColor(
            if (selected) 0xFFFFFFFF.toInt()
            else ContextCompat.getColor(this, R.color.button_on_secondary)
        )
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
            else -> "마이현대 계정 연동 완료"
        }

        // 여기서 '카드 등록' 버튼을 누르면 은행사 선택 화면으로 넘어갑니다.
        btnOAuthPendingRegisterCard.setOnClickListener {
            Log.d(TAG, "btnOAuthPendingRegisterCard clicked")
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

        val userName  = ParkingStateManager.getHyundaiUserName(this)
        val modelName = ParkingStateManager.getHyundaiModelName(this)
        val plate = ParkingStateManager.getPlateNumber(this)
        if (plate.isNullOrBlank()) {
            tvPlateNumber.text = modelName.ifBlank { "차량 연동 완료" }
            tvVinShort.text = "번호판 미등록 · ${maskVin(vin)}"
        } else {
            tvPlateNumber.text = plate
            tvVinShort.text = if (modelName.isNotEmpty()) "$modelName  ${maskVin(vin)}" else maskVin(vin)
        }

        // ── 카드 영역: '카드 미등록' 플레이스홀더 ─────────────────────────────
        mainCardBody.background = roundedBackground(0xFFF4F7FB.toInt(), 0xFFDCE5F0.toInt())
        mainCardBrand.text   = "카드 미등록"
        mainCardBrand.setTextColor(0xFF6B7684.toInt())
        mainCardNetwork.text = "—"
        mainCardNetwork.setTextColor(0xFF8B95A1.toInt())
        mainCardNumber.text  = "•••• •••• •••• ••••"
        mainCardNumber.setTextColor(0xFF8B95A1.toInt())

        // 상태바: 결제 대기
        tvPaymentStatus.text = "카드 등록 전 — 자동 결제 대기 중"
        tvStatusDot.setTextColor(0xFFF59E0B.toInt())
        updateParkingBadge(false)

        // ── 메인 CTA: '카드 등록하기' ────────────────────────────────────────
        val btnRegisterCard = findViewById<Button>(R.id.btnRegisterCard)
        btnRegisterCard.text = "카드 등록하기"
        btnRegisterCard.setOnClickListener { launchCardRegistrationOnly() }

        // 계정 재연동 버튼은 그대로 유지
        findViewById<Button>(R.id.btnChangeCard).setOnClickListener {
            val accountLabel = if (userName.isNotEmpty()) "$userName 님 계정" else "마이현대 계정"
            showAaosDialog(
                "계정 재연동",
                "$accountLabel\n\nQR 스캔으로 마이현대 계정을 다시 연동하시겠습니까?",
                "취소" to {},
                "재연동" to { startActivityForResult(Intent(this, RegistrationActivity::class.java), 100) }
            )
        }

        // 카드가 없으므로 정산 버튼은 카드 등록을 유도
        btnSettleNow.setOnClickListener {
            Toast.makeText(this, "먼저 카드를 등록해 주세요.", Toast.LENGTH_SHORT).show()
            launchCardRegistrationOnly()
        }

        // 결제 관련 섹션은 숨김 / 비움
        hideParkingActiveSection()
        findViewById<LinearLayout>(R.id.layoutTxHistory).removeAllViews()

        // 카드 미등록 상태 — 결제 처리 불가이므로 위치 권한 요청(startServicesAndListeners)은 생략.
        // 카드 등록 완료 후 showRegisteredState()에서 서비스가 시작된다.
        // 주차장 목록은 정보용으로 노출 (탭하면 내비게이션은 가능)
        populateParkingLots()
        showRegisteredFeature(RegisteredFeature.CARD)
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

        mainCardBody.background = roundedBackground(theme.bgColor)
        mainCardBrand.text = theme.shortName
        mainCardBrand.setTextColor(theme.brandTextColor)
        mainCardNetwork.text = theme.network
        mainCardNetwork.setTextColor(0xFFEAF3FF.toInt())
        mainCardNumber.text  = "•••• •••• •••• $lastFour"
        mainCardNumber.setTextColor(0xFFFFFFFF.toInt())

        val btnRegisterCardRegistered = findViewById<Button>(R.id.btnRegisterCard)
        // showLoggedInNoCardState 에서 '카드 등록하기' 로 변경되었을 수 있으므로 원래 라벨로 복구
        btnRegisterCardRegistered.text = "카드 등록"
        btnRegisterCardRegistered.setOnClickListener {
            showAaosDialog(
                "카드 변경",
                "새 카드를 등록합니다.\n번호판 확인 후 카드 정보를 입력해 주세요.",
                "취소" to {},
                "변경하기" to { launchCardRegistrationOnly() }
            )
        }

        findViewById<Button>(R.id.btnChangeCard).setOnClickListener {
            val accountLabel = if (userName.isNotEmpty()) "$userName 님 계정" else "마이현대 계정"
            showAaosDialog(
                "계정 재연동",
                "$accountLabel\n\nQR 스캔으로 마이현대 계정을 다시 연동하시겠습니까?",
                "취소" to {},
                "재연동" to { startActivityForResult(Intent(this, RegistrationActivity::class.java), 100) }
            )
        }

        btnSettleNow.setOnClickListener { queryFeeAndShowSettlement() }

        if (ParkingStateManager.isParked(this)) {
            val lotId = ParkingStateManager.getLotId(this)
            showParkingActiveSection(lotId)
            tvPaymentStatus.text = "주차 중 — 지금 정산하기 가능"
            tvStatusDot.setTextColor(0xFFF59E0B.toInt())
            updateParkingBadge(true)
        } else {
            hideParkingActiveSection()
            tvPaymentStatus.text = "주차장 접근 시 자동 결제됩니다"
            tvStatusDot.setTextColor(0xFF12B981.toInt())
            updateParkingBadge(false)
        }

        populateParkingLots()
        refreshTransactionHistory()
        showRegisteredFeature(RegisteredFeature.PARKING)
    }

    private fun launchCardRegistrationOnly() {
        Log.d(TAG, "launchCardRegistrationOnly() called")
        val token = ParkingStateManager.getAccessToken(this)
        if (token.isNullOrBlank()) {
            Log.w(TAG, "Card registration blocked: missing access token")
            showAaosDialog("로그인 필요", "로그인 토큰이 없습니다.\nQR 로그인을 다시 진행해 주세요.", "확인" to {})
            return
        }
        val carId = ParkingStateManager.getHyundaiCarId(this)
        if (carId.isBlank()) {
            Log.w(TAG, "Card registration blocked: missing carId")
            showAaosDialog("차량 정보 없음", "연결된 차량 정보가 없습니다.\nQR 로그인을 다시 진행해 주세요.", "확인" to {})
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
            showAaosDialog("화면 오류", "카드 등록 화면을 열 수 없습니다.\n${it.message}", "확인" to {})
        }
    }

    private fun getCardBrandTheme(brandName: String): BrandTheme {
        return when (brandName) {
            "현대", "현대카드" -> BrandTheme("HYUNDAI", 0xFF3182F6.toInt(), 0xFFFFFFFF.toInt(), "VISA")
            "KB국민", "KB" -> BrandTheme("KB", 0xFFB8874A.toInt(), 0xFFFFF7E8.toInt(), "MASTER")
            "신한", "신한카드" -> BrandTheme("SHINHAN", 0xFFE24B4B.toInt(), 0xFFFFF2F2.toInt(), "VISA")
            "삼성", "삼성카드" -> BrandTheme("SAMSUNG", 0xFF2563EB.toInt(), 0xFFEAF3FF.toInt(), "MASTER")
            "롯데", "롯데카드" -> BrandTheme("LOTTE", 0xFFD23F5B.toInt(), 0xFFFFF1F4.toInt(), "VISA")
            "우리", "우리카드" -> BrandTheme("WOORI", 0xFF0B7FAB.toInt(), 0xFFE8F7FF.toInt(), "MASTER")
            "하나", "하나카드" -> BrandTheme("HANA", 0xFF0F8F68.toInt(), 0xFFE8FFF6.toInt(), "VISA")
            else -> BrandTheme("CARD", 0xFF0F3A6D.toInt(), 0xFFEAF3FF.toInt(), "VISA")
        }
    }

    private fun updateParkingBadge(isParked: Boolean) {
        if (isParked) {
            tvParkingBadge.text = "🅿 주차 중"
            tvParkingBadge.setTextColor(0xFF0F8F68.toInt())
            tvParkingBadge.background = roundedBackground(0xFFE8FFF6.toInt(), 0xFFB8EEDB.toInt())
        } else {
            tvParkingBadge.text = "미주차"
            tvParkingBadge.setTextColor(0xFF1B64DA.toInt())
            tvParkingBadge.background = roundedBackground(0xFFEEF6FF.toInt(), 0xFFCFE3FF.toInt())
        }
    }

    private fun showParkingActiveSection(lotId: String) {
        layoutParkingActive.visibility = View.VISIBLE
        tvActiveLotName.text = if (lotId.isNotEmpty()) lotId else "주차장"

        if (parkingStartMs == 0L) {
            // 저장된 입차 시간이 있으면 복원, 없으면 현재 시각으로 초기화
            val savedEntry = ParkingStateManager.getEntryTimeMs(this)
            parkingStartMs = if (savedEntry > 0L) savedEntry else System.currentTimeMillis()
        }

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
        val hasLocation = NaviHelper.currentLat != 0.0 && NaviHelper.currentLng != 0.0

        fun distMeters(lot: GeofenceManager.ParkingLot): Float {
            val results = FloatArray(1)
            Location.distanceBetween(NaviHelper.currentLat, NaviHelper.currentLng, lot.lat, lot.lng, results)
            return results[0]
        }

        val lots = if (hasLocation)
            GeofenceManager.cachedParkingLots.sortedBy { distMeters(it) }
        else
            GeofenceManager.cachedParkingLots.sortedBy { it.name }

        lots.forEach { lot ->
            val distM = if (hasLocation) distMeters(lot) else null
            val distText = when {
                distM == null -> "탭하여 내비게이션 시작"
                distM < 1000  -> "${distM.toInt()} m · 약 ${(distM / 500).toInt() + 1}분"
                else          -> "${"%.1f".format(distM / 1000)} km · 약 ${(distM / 500).toInt() + 1}분"
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), dp(14), dp(16), dp(14))
                background = getDrawable(R.drawable.bg_card_dark); isClickable = true; isFocusable = true
                foreground = TypedValue().let { tv -> theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true); getDrawable(tv.resourceId) }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.setMargins(0, 0, 0, dp(8)) }
            }
            val tvIcon = TextView(this).apply { text = "📍"; textSize = 16f }
            val tvInfo = TextView(this).apply {
                text = "${lot.name}\n$distText"
                setTextColor(Color.parseColor("#191F28")); textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also { it.setMargins(dp(10), 0, 0, 0) }
            }
            val tvBadge = TextView(this).apply { text = "제휴"; setTextColor(Color.parseColor("#0F8F68")); textSize = 11f }
            row.addView(tvIcon); row.addView(tvInfo); row.addView(tvBadge); layoutParkingLots.addView(row)
            row.setOnClickListener { startNavigationTo(lot) }
        }
    }

    private fun startNavigationTo(lot: GeofenceManager.ParkingLot) {
        if (!hasCompletedCardRegistration()) {
            Log.w(TAG, "카드 미등록 상태에서 내비 시작")
        }
        showAaosDialog(
            "🧭 내비게이션 시작",
            "${lot.name}\n\n목적지로 경로 안내를 시작합니다.\n도착 전 차량 정보가 주차장에 자동으로 등록됩니다.",
            "취소" to {},
            "시작" to {
                // pre-notify는 내비 성공 여부와 무관하게 즉시 전송
                val token = ParkingStateManager.getAccessToken(this)
                if (token != null) {
                    Thread {
                        runCatching { ApiManager.sendPreNotification(lot.id, token) }
                            .onSuccess { Log.d(TAG, "사전 알림 전송 완료: ${lot.id}") }
                            .onFailure { Log.e(TAG, "사전 알림 실패: ${it.javaClass.simpleName} ${it.message}") }
                    }.start()
                } else {
                    Log.e(TAG, "사전 알림 실패: access token null")
                }
                val navStarted = NaviHelper.setDestination(this, lot.lat, lot.lng, lot.name, lot.id)
                if (navStarted) {
                    handler.postDelayed({
                        Thread { NaviHelper.reacquirePanelControl(applicationContext) }.start()
                    }, 1_500)
                } else {
                    Toast.makeText(this, "내비게이션을 시작할 수 없습니다", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun styleDialogButtons(dialog: AlertDialog) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.BLACK)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Color.BLACK)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(Color.BLACK)
    }

    /**
     * AlertDialog 대신 window.decorView에 직접 overlay를 추가한다.
     * AlertDialog는 별도 Window를 생성하므로 Pleos panel ownership이 적용 안 됨.
     * 같은 Window 안에 overlay를 그리면 takePanelControl()의 효과가 그대로 유지된다.
     * buttons: Pair(라벨, 클릭 액션) — 마지막 버튼이 파란색 primary 취급
     */
    private fun showAaosDialog(
        title: String,
        message: String,
        vararg buttons: Pair<String, () -> Unit>,
        cancelable: Boolean = true,
        customView: android.view.View? = null
    ) {
        val decorView = window.decorView as android.widget.FrameLayout

        val overlay = android.widget.FrameLayout(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xAA000000.toInt())
            elevation = 999f
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(0xFFFFFFFF.toInt())
            setPadding(dp(24), dp(20), dp(24), dp(8))
            val lp = android.widget.FrameLayout.LayoutParams(
                (resources.displayMetrics.widthPixels * 0.78).toInt(),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = android.view.Gravity.CENTER
            layoutParams = lp
        }

        card.addView(TextView(this).apply {
            text = title
            setTextColor(0xFF191F28.toInt())
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(10) }
        })
        card.addView(TextView(this).apply {
            text = message
            setTextColor(0xFF4E5968.toInt())
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(8) }
        })
        customView?.let { v ->
            card.addView(v, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(8) })
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        fun dismiss() { runOnUiThread { decorView.removeView(overlay) } }

        buttons.forEachIndexed { i, (label, action) ->
            btnRow.addView(Button(this).apply {
                text = label
                textSize = 14f
                setTextColor(if (i == buttons.lastIndex) 0xFF1B64DA.toInt() else Color.BLACK)
                setBackgroundColor(Color.TRANSPARENT)
                minHeight = dp(48)
                setPadding(dp(8), 0, dp(8), 0)
                setOnClickListener { dismiss(); action() }
            })
        }
        card.addView(btnRow)

        overlay.addView(card)
        overlay.setOnClickListener { if (cancelable) dismiss() }
        card.setOnClickListener { }

        decorView.addView(overlay)
    }

    private fun startServicesAndListeners() {
        registerServiceCallbacks()
        handler.postDelayed({
            tvStatusDot.setTextColor(
                if (MqttManager.isConnected()) 0xFF12B981.toInt() else 0xFF8B95A1.toInt()
            )
        }, 2_000)
        try {
            CarPayInService.start(this)
        } catch (t: Throwable) {
            Log.e(TAG, "CarPayInService 시작 실패 (앱은 계속 동작): ${t.javaClass.simpleName} ${t.message}")
        }
    }

    private fun registerServiceCallbacks() {
        CarPayInService.onFeeUpdated = { lotName, amount, _ -> tvActiveLotName.text = lotName; tvParkingEstFee.text = "%,d원".format(amount); lastAmount = amount }
        CarPayInService.onParkingConfirmed = { lotId, _ ->
            parkingStartMs = System.currentTimeMillis(); showParkingActiveSection(lotId); tvPaymentStatus.text = "주차 중 — 지금 정산하기 가능"
            tvStatusDot.setTextColor(0xFFF59E0B.toInt()); updateParkingBadge(true); showEntryConfirmed(lotId)
        }
        CarPayInService.onPaymentComplete = { txId, approvalNo, lotId, amount ->
            hideParkingActiveSection(); tvPaymentStatus.text = "주차장 접근 시 자동 결제됩니다"; tvStatusDot.setTextColor(0xFF12B981.toInt())
            updateParkingBadge(false); refreshTransactionHistory(); showPaymentComplete(txId, approvalNo, lotId, amount)
        }
        CarPayInService.onConnectionChanged = { connected -> tvStatusDot.setTextColor(if (connected) 0xFF12B981.toInt() else 0xFF8B95A1.toInt()) }
    }

    private fun showEntryConfirmed(lotId: String) {
        showAaosDialog(
            "🅿 입차 확인",
            "$lotId\n\n입차가 확인되었습니다.\n시동을 켜거나 [지금 정산하기] 버튼으로 정산할 수 있습니다.",
            "확인" to {},
            cancelable = false
        )
    }

    private fun queryFeeAndShowSettlement() {
        val lotId = ParkingStateManager.getLotId(this)
        val sessionId = ParkingStateManager.getSessionId(this)
        tvPaymentStatus.text = "요금 조회 중..."; tvStatusDot.setTextColor(0xFFF59E0B.toInt()); btnSettleNow.isEnabled = false

        Thread {
            try {
                val fee = ApiManager.withAutoRefresh(this) { token -> ApiManager.queryFee(lotId, sessionId, token) }
                handler.post { btnSettleNow.isEnabled = true; showSettlementDialog(fee, sessionId) }
            } catch (e: Exception) {
                handler.post { tvPaymentStatus.text = "요금 조회 실패"; tvStatusDot.setTextColor(0xFFE5484D.toInt()); btnSettleNow.isEnabled = true }
            }
        }.start()
    }

    private fun showSettlementDialog(fee: ApiManager.FeeResult, sessionId: String) {
        lastAmount = fee.amount
        val h = fee.durationMinutes / 60
        val m = fee.durationMinutes % 60
        val dur = if (h > 0) "${h}시간 ${m}분" else "${m}분"
        showAaosDialog(
            "🅿 정산 확인",
            "${fee.lotName}\n\n주차 시간: $dur\n결제 금액: ${"%,d".format(fee.amount)}원\n\n정산하시겠습니까?",
            "취소" to { tvPaymentStatus.text = "취소됨 — 출구에서 현장 정산"; tvStatusDot.setTextColor(0xFFE5484D.toInt()); btnSettleNow.isEnabled = true },
            "예" to { processPayment(sessionId, fee.amount) },
            cancelable = false
        )
    }

    private fun processPayment(sessionId: String, amount: Int) {
        tvPaymentStatus.text = "결제 처리 중..."; tvStatusDot.setTextColor(0xFFF59E0B.toInt()); btnSettleNow.isEnabled = false

        Thread {
            try {
                val result = ApiManager.withAutoRefresh(this) { token -> ApiManager.requestPayment(sessionId, amount, token) }
                val lotId = ParkingStateManager.getLotId(this)
                TransactionStore.save(this, result.transactionId, lotId, amount)
                ParkingStateManager.saveParkingState(this, false)
                handler.post {
                    hideParkingActiveSection(); tvPaymentStatus.text = "주차장 접근 시 자동 결제됩니다"; tvStatusDot.setTextColor(0xFF12B981.toInt())
                    updateParkingBadge(false); refreshTransactionHistory(); showPaymentComplete(result.transactionId, result.approvalNumber, lotId, amount)
                }
            } catch (e: Exception) {
                handler.post { btnSettleNow.isEnabled = true; showPaymentError("결제 오류: ${e.message}") }
            }
        }.start()
    }

    private fun showPaymentComplete(txId: String, approvalNo: String, lotId: String, amount: Int) {
        showAaosDialog(
            "✓ 결제 완료",
            "$lotId\n\n결제 금액: ${"%,d".format(amount)}원\n승인번호: $approvalNo\n거래번호: ${txId.take(14)}…\n\n약 3~5초 후 차단기가 개방됩니다.",
            "확인" to {},
            cancelable = false
        )
    }

    private fun showPaymentError(message: String) {
        tvPaymentStatus.text = "결제 실패"; tvStatusDot.setTextColor(0xFFE5484D.toInt())
        showAaosDialog(
            "⚠ 결제 실패",
            "$message\n\n다른 결제 수단을 사용하거나 현장 무인정산기를 이용해 주세요.",
            "현장 정산" to {},
            "재시도" to { queryFeeAndShowSettlement() },
            cancelable = false
        )
    }

    private fun refreshTransactionHistory() {
        val container = findViewById<LinearLayout>(R.id.layoutTxHistory)
        container.removeAllViews()
        val transactions = TransactionStore.load(this)

        if (transactions.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "아직 결제 내역이 없습니다"
                setTextColor(0xFF8B95A1.toInt())
                textSize = 13f
                gravity = android.view.Gravity.CENTER
                setPadding(dp(16), dp(20), dp(16), dp(20))
                background = roundedBackground(0xFFF4F7FB.toInt(), 0xFFDCE5F0.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
            return
        }

        transactions.forEach { tx ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = roundedBackground(0xFFFFFFFF.toInt(), 0xFFE4EAF2.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.setMargins(0, 0, 0, dp(10)) }
            }

            val left = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            left.addView(TextView(this).apply {
                text = tx.lotId.ifBlank { "주차장" }
                setTextColor(0xFF191F28.toInt())
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            left.addView(TextView(this).apply {
                text = "${TransactionStore.formatDate(tx.timestamp)} · ${tx.transactionId.take(12)}"
                setTextColor(0xFF8B95A1.toInt())
                textSize = 11f
                setPadding(0, dp(3), 0, 0)
            })

            val amount = TextView(this).apply {
                text = TransactionStore.formatAmount(tx.amount)
                setTextColor(0xFF1B64DA.toInt())
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }

            row.addView(left)
            row.addView(amount)
            container.addView(row)
        }
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
        window.decorView.requestFocus()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        // static 콜백에서 Activity 참조 해제 (메모리 누수 방지)
        CarPayInService.onFeeUpdated       = null
        CarPayInService.onParkingConfirmed = null
        CarPayInService.onPaymentComplete  = null
        CarPayInService.onConnectionChanged= null
        NaviHelper.release()
        VehicleDataManager.release()
    }

    private fun setupDevTrigger() {
        btnDevMenuHidden.setOnClickListener { showDevMenu() }
        btnResetApp.setOnClickListener { confirmReset() }
    }

    private fun showDevMenu() {
        if (isDevMenuVisible) return
        isDevMenuVisible = true
        val decorView = window.decorView as android.widget.FrameLayout

        val overlay = android.widget.FrameLayout(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xAA000000.toInt())
            elevation = 999f
        }
        fun dismissOverlay() {
            runOnUiThread {
                isDevMenuVisible = false
                decorView.removeView(overlay)
            }
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(0xFFFFFFFF.toInt())
            val lp = android.widget.FrameLayout.LayoutParams(
                (resources.displayMetrics.widthPixels * 0.78).toInt(),
                (resources.displayMetrics.heightPixels * 0.75).toInt()
            )
            lp.gravity = android.view.Gravity.CENTER
            layoutParams = lp
        }

        card.addView(TextView(this).apply {
            text = "개발자 메뉴"
            setTextColor(0xFF191F28.toInt()); textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(20), dp(16), dp(20), dp(8))
        })

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }

        fun addBtn(label: String, action: () -> Unit) {
            container.addView(Button(this).apply {
                text = label; textSize = 14f
                setTextColor(0xFF191F28.toInt())
                gravity = android.view.Gravity.CENTER
                background = roundedBackground(0xFFF4F7FB.toInt(), 0xFFDCE5F0.toInt())
                setPadding(dp(16), 0, dp(16), 0)
                minHeight = dp(56)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
                ).also { it.setMargins(0, 0, 0, dp(8)) }
                setOnClickListener { dismissOverlay(); action() }
            })
        }

        addBtn("MQTT 재연결") {
            val carId = ParkingStateManager.getHyundaiCarId(this)
            if (carId.isNotBlank()) Thread { MqttManager.connect(applicationContext, carId) }.start()
            Toast.makeText(this, "MQTT 재연결 시도", Toast.LENGTH_SHORT).show()
        }
        addBtn("번호판 설정") {
            val et = android.widget.EditText(this).apply {
                hint = "예) 12가3456"; inputType = android.text.InputType.TYPE_CLASS_TEXT
                val saved = ParkingStateManager.getPlateNumber(this@MainActivity)
                if (!saved.isNullOrBlank()) setText(saved)
            }
            showAaosDialog("번호판 직접 설정", "카드 등록 시 자동으로 채워집니다.",
                "취소" to {},
                "저장" to {
                    val plate = java.text.Normalizer.normalize(
                        et.text.toString().replace("\\s|-|\\.|·".toRegex(), ""),
                        java.text.Normalizer.Form.NFC
                    )
                    if (plate.isNotBlank()) {
                        ParkingStateManager.savePlateNumber(this, plate)
                        Toast.makeText(this, "번호판 저장: $plate", Toast.LENGTH_SHORT).show()
                    }
                },
                customView = et
            )
        }
        addBtn("VIN 표시") { Toast.makeText(this, "VIN: $vin", Toast.LENGTH_LONG).show() }
        addBtn("액세스 토큰 표시") {
            Thread {
                val token = runCatching {
                    val refresh = ParkingStateManager.getRefreshToken(this)
                    if (refresh != null) {
                        val result = ApiManager.refreshToken(refresh)
                        ParkingStateManager.saveTokens(this, result.accessToken, result.refreshToken)
                        result.accessToken
                    } else {
                        ParkingStateManager.getAccessToken(this) ?: "(토큰 없음)"
                    }
                }.getOrElse { ParkingStateManager.getAccessToken(this) ?: "(갱신 실패)" }
                Log.d(TAG, "DEV_TOKEN: $token")
                handler.post {
                    val et = android.widget.EditText(this).apply {
                        setText(token)
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
                        isFocusable = true
                        isFocusableInTouchMode = true
                        setTextIsSelectable(true)
                        selectAll()
                    }
                    showAaosDialog("액세스 토큰 (전체 선택 후 복사)", "", "확인" to {}, customView = et)
                }
            }.start()
        }
        addBtn("앱 완전 초기화") {
            clearRegistrationState(); setIntent(Intent(this, MainActivity::class.java))
            renderStateFromStorage(); Toast.makeText(this, "초기화 완료", Toast.LENGTH_SHORT).show()
        }

        card.addView(android.widget.ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            addView(container)
        })
        card.addView(Button(this).apply {
            text = "닫기"; textSize = 14f; setTextColor(0xFF1B64DA.toInt())
            setBackgroundColor(Color.TRANSPARENT); minHeight = dp(48)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(dp(12), 0, dp(12), dp(8)) }
            setOnClickListener { dismissOverlay() }
        })

        overlay.addView(card)
        overlay.setOnClickListener { dismissOverlay() }
        card.setOnClickListener { }
        decorView.addView(overlay)
    }

    private fun showCardInfoDialog() {
        val brand = ParkingStateManager.getCardBrand(this)
        val lastFour = ParkingStateManager.getCardLastFour(this)
        val hasCard = hasCompletedCardRegistration()
        val message = if (hasCard) {
            "카드사: ${brand.ifBlank { "등록된 카드" }}\n카드번호: **** $lastFour\n상태: 자동 결제 사용 가능"
        } else {
            "아직 등록된 카드가 없습니다.\n카드 등록 버튼을 눌러 결제 수단을 연결해 주세요."
        }
        if (hasCard) {
            showAaosDialog("카드 정보", message, "확인" to {})
        } else {
            showAaosDialog("카드 정보", message, "취소" to {}, "카드 등록" to { launchCardRegistrationOnly() })
        }
    }

    private fun showVehicleInfoDialog() {
        val plate = ParkingStateManager.getPlateNumber(this) ?: "미등록"
        val modelName = ParkingStateManager.getHyundaiModelName(this)
        val userName = ParkingStateManager.getHyundaiUserName(this)
        val message = buildString {
            append("차량번호: $plate\n")
            if (modelName.isNotEmpty()) append("모델: $modelName\n")
            if (userName.isNotEmpty()) append("연동 계정: $userName 님\n")
            append(maskVin(vin))
        }
        showAaosDialog("차량 정보", message, "확인" to {})
    }

    private fun showParkingInfoDialog() {
        val lotId = ParkingStateManager.getLotId(this).ifBlank { "주차장" }
        val message = if (ParkingStateManager.isParked(this)) {
            "$lotId\n\n주차 시간: ${tvParkingTimer.text}\n예상 요금: ${tvParkingEstFee.text}"
        } else {
            "현재 주차 중인 세션은 없습니다.\n주차장 메뉴에서 제휴 주차장을 선택하면 내비게이션을 시작할 수 있습니다."
        }
        showAaosDialog("주차 정보", message, "확인" to {})
    }

    private fun confirmReset() {
        showAaosDialog(
            "⚠ 등록 해제",
            "등록된 카드와 차량 정보를 모두 삭제합니다.\n계속하시겠습니까?",
            "취소" to {},
            "삭제" to { clearRegistrationState(); setIntent(Intent(this, MainActivity::class.java)); renderStateFromStorage() }
        )
    }

    private fun roundedBackground(
        fillColor: Int,
        strokeColor: Int? = null
    ): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(fillColor)
            cornerRadius = dp(8).toFloat()
            strokeColor?.let { setStroke(dp(1), it) }
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun maskVin(vin: String): String = if (vin.length >= 6) "VIN: ${vin.take(3)}•••${vin.takeLast(3)}" else "VIN: $vin"

    private fun hasOAuthSession(): Boolean {
        return !ParkingStateManager.getAccessToken(this).isNullOrBlank() &&
            ParkingStateManager.getHyundaiCarId(this).isNotBlank()
    }

    private fun clearRegistrationState() {
        val token = ParkingStateManager.getAccessToken(this)
        CarPayInService.stop(this)
        handler.removeCallbacks(timerRunnable)
        ParkingStateManager.clearSession(this)
        TransactionStore.clear(this)
    }

    private fun hasCompletedCardRegistration(): Boolean {
        if (!ParkingStateManager.isRegistered(this)) return false
        val lastFour = ParkingStateManager.getCardLastFour(this)
        return lastFour.isNotBlank() && lastFour != "****" && lastFour != "0000"
    }
}
