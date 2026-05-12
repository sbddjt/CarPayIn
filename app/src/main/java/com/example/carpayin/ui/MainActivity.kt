package com.example.carpayin.ui

import com.example.carpayin.R
import android.content.Intent
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

    // ── VIN ──────────────────────────────────────────────────────────────────
    private var vin: String = ""

    // ── 지오펜스 접근 중인 주차장 ─────────────────────────────────────────────
    private var approachingLotId: String? = null

    // ── 내비게이션 중인 주차장 ────────────────────────────────────────────────
    private var navigatingLotId: String? = null

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var tvStatusDot: TextView
    private lateinit var tvPaymentStatus: TextView
    private lateinit var tvParkingBadge: TextView
    private lateinit var tvVinShort: TextView
    private lateinit var tvPlateNumber: TextView
    private lateinit var layoutUnregistered: LinearLayout
    private lateinit var layoutRegistered: ScrollView
    private lateinit var tvHeaderTitle: RelativeLayout

    // 추가: 초기화 버튼 및 카드 뷰 UI
    private lateinit var btnResetApp: Button
    private lateinit var mainCardBody: LinearLayout
    private lateinit var mainCardBrand: TextView
    private lateinit var mainCardNetwork: TextView
    private lateinit var mainCardNumber: TextView

    // 주차 중 섹션
    private lateinit var layoutParkingActive: LinearLayout
    private lateinit var tvActiveLotName: TextView
    private lateinit var tvParkingTimer: TextView
    private lateinit var tvParkingEstFee: TextView
    private lateinit var btnSettleNow: Button

    // 제휴 주차장 목록
    private lateinit var layoutParkingLots: LinearLayout

    // ── 상태 ──────────────────────────────────────────────────────────────────
    private var lastAmount: Int = 0
    private var parkingStartMs: Long = 0L
    private val timerRunnable = object : Runnable {
        override fun run() {
            updateParkingTimer()
            handler.postDelayed(this, 1_000)
        }
    }

    // ── 개발자 트리거 ────────────────────────────────────────────────────────
    private var devTapCount = 0
    private val devTapResetRunnable = Runnable { devTapCount = 0 }
    private val DEV_TAP_TARGET = 5
    private val DEV_TAP_WINDOW_MS = 3000L
    private val DEV_PIN = "1234"

    // ── 카드 테마 데이터 클래스 ───────────────────────────────────────────────
    data class BrandTheme(val shortName: String, val bgColor: Int, val brandTextColor: Int, val network: String)

    // ─────────────────────────────────────────────────────────────────────────
    // onCreate
    // ─────────────────────────────────────────────────────────────────────────

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

        // 상단 초기화(등록 해제) 버튼 리스너
        btnResetApp.setOnClickListener {
            confirmReset()
        }

        setupDevTrigger()

        VehicleDataManager.init(this)
        vin = VehicleDataManager.readVin(this)

        // NaviHelper SDK 초기화
        NaviHelper.init(this)

        if (ParkingStateManager.isRegistered(this)) {
            showRegisteredState()
            startServicesAndListeners()
        } else if (ParkingStateManager.isOAuthComplete(this)) {
            // OAuth는 완료됐지만 카드 등록을 안 한 채로 앱을 껐다가 재진입
            // QR 화면 없이 바로 카드 등록 화면으로
            launchCardRegistrationOnly()
        } else {
            showUnregisteredState()
        }
    }

    private fun showUnregisteredState() {
        layoutUnregistered.visibility = View.VISIBLE
        layoutRegistered.visibility   = View.GONE
        btnResetApp.visibility        = View.GONE

        findViewById<Button>(R.id.btnRegister).setOnClickListener {
            startActivityForResult(Intent(this, RegistrationActivity::class.java), 100)
        }
    }

    /**
     * OAuth 완료 후 카드 미등록 상태에서 재진입 시 호출.
     * RegistrationActivity(QR) 없이 CardRegistrationActivity 직접 시작.
     */
    private fun launchCardRegistrationOnly() {
        layoutUnregistered.visibility = View.GONE
        layoutRegistered.visibility   = View.GONE
        val intent = Intent(this, CardRegistrationActivity::class.java).apply {
            putExtra(CardRegistrationActivity.EXTRA_VIN,
                VehicleDataManager.readVin(this@MainActivity))
            putExtra(CardRegistrationActivity.EXTRA_ACCESS_TOKEN,
                ParkingStateManager.getAccessToken(this@MainActivity) ?: "")
            putExtra(CardRegistrationActivity.EXTRA_USER_NAME,
                ParkingStateManager.getHyundaiUserName(this@MainActivity))
        }
        startActivityForResult(intent, 101)
    }

    private fun showRegisteredState() {
        layoutUnregistered.visibility = View.GONE
        layoutRegistered.visibility   = View.VISIBLE
        btnResetApp.visibility        = View.VISIBLE

        // ── 차량 정보 ─────────────────────────────────────────────────────────
        tvVinShort.text    = maskVin(vin)
        tvPlateNumber.text = ParkingStateManager.getPlateNumber(this) ?: "—"

        // ── 마이현대 계정 정보 표시 ───────────────────────────────────────────
        val userName  = ParkingStateManager.getHyundaiUserName(this)
        val modelName = ParkingStateManager.getHyundaiModelName(this)

        // 차량 모델명이 있으면 VIN 자리에 함께 표시
        if (modelName.isNotEmpty()) {
            tvVinShort.text = "$modelName  ${maskVin(vin)}"
        }

        // ── 마이현대 연동 결제 수단 카드 UI ──────────────────────────────────
        val brand    = ParkingStateManager.getCardBrand(this)
        val lastFour = ParkingStateManager.getCardLastFour(this)
        val theme    = getCardBrandTheme(brand)

        mainCardBody.setBackgroundColor(theme.bgColor)
        mainCardBrand.text = theme.shortName
        mainCardBrand.setTextColor(theme.brandTextColor)
        mainCardNetwork.text = theme.network
        mainCardNumber.text  = "•••• •••• •••• $lastFour"

        // 계정 재연동 (마이현대 재로그인)
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

        // 지금 정산하기 버튼
        btnSettleNow.setOnClickListener {
            queryFeeAndShowSettlement()
        }

        // 주차 상태
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

    // ─────────────────────────────────────────────────────────────────────────
    // 주차 중 활성 섹션
    // ─────────────────────────────────────────────────────────────────────────

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
        val token = ParkingStateManager.getAccessToken(this) ?: return
        Thread {
            try {
                val fee = ApiManager.queryFee(lotId, sessionId, token)
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

    // ─────────────────────────────────────────────────────────────────────────
    // 제휴 주차장 목록
    // ─────────────────────────────────────────────────────────────────────────

    private fun populateParkingLots() {
        layoutParkingLots.removeAllViews()

        // 접근 중 → 내비 중 순서로 우선 정렬
        val sorted = GeofenceManager.cachedParkingLots.sortedWith(
            compareByDescending<GeofenceManager.ParkingLot> { it.id == approachingLotId }
                .thenByDescending { it.id == navigatingLotId }
        )

        sorted.forEach { lot ->
            val isNearby    = lot.id == approachingLotId
            val isNavigating = lot.id == navigatingLotId

            // ── 행 배경 결정 ────────────────────────────────────────────────
            val rowBg = when {
                isNearby -> android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF1A2200.toInt())
                    setStroke(2, 0xFFFFD700.toInt())
                    cornerRadius = 12f
                }
                isNavigating -> android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF001A33.toInt())
                    setStroke(2, 0xFF00AAFF.toInt())
                    cornerRadius = 12f
                }
                else -> getDrawable(R.drawable.bg_card_dark)
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(16, 14, 16, 14)
                background = rowBg
                isClickable = true
                isFocusable = true
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, 0, 0, 8)
                layoutParams = lp
            }

            // ── 아이콘 ──────────────────────────────────────────────────────
            val tvIcon = TextView(this).apply {
                text = when {
                    isNavigating -> "🧭"
                    isNearby     -> "🚗"
                    else         -> "📍"
                }
                textSize = 16f
            }

            // ── 주차장 이름 + 상태 텍스트 ───────────────────────────────────
            val subText = when {
                isNavigating -> "내비게이션 안내 중 · 탭하여 취소"
                isNearby     -> "접근 중 · 사전 등록됨"
                else         -> "탭하여 내비게이션 시작"
            }
            val tvInfo = TextView(this).apply {
                text = "${lot.name}\n$subText"
                setTextColor(when {
                    isNavigating -> Color.parseColor("#00AAFF")
                    isNearby     -> Color.parseColor("#FFD700")
                    else         -> Color.WHITE
                })
                textSize = 13f
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                lp.setMargins(10, 0, 0, 0)
                layoutParams = lp
            }

            // ── 우측 상태 배지 ───────────────────────────────────────────────
            val tvBadge = TextView(this).apply {
                text = when {
                    isNavigating -> "안내 중"
                    isNearby     -> "사전 등록됨"
                    else         -> "제휴"
                }
                setTextColor(when {
                    isNavigating -> Color.parseColor("#00AAFF")
                    isNearby     -> Color.parseColor("#FFD700")
                    else         -> Color.parseColor("#00AA55")
                })
                textSize = 11f
            }

            row.addView(tvIcon)
            row.addView(tvInfo)
            row.addView(tvBadge)
            layoutParkingLots.addView(row)

            // ── 클릭 리스너: 내비게이션 시작 / 취소 토글 ───────────────────
            row.setOnClickListener {
                if (isNavigating) {
                    // 안내 중 → 탭하면 취소
                    confirmCancelNavigation(lot)
                } else {
                    // 내비게이션 시작
                    startNavigationTo(lot)
                }
            }
        }
    }

    /**
     * 주차장을 선택하면:
     *  1. NaviHelper SDK로 목적지 설정 → Pleos 내비게이션 경로 안내 시작
     *  2. 사전 알림 전송 (아직 안 보낸 경우)
     *  3. 해당 주차장 행을 파란색 "안내 중" 상태로 전환
     */
    private fun startNavigationTo(lot: GeofenceManager.ParkingLot) {
        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert)
            .setTitle("🧭 내비게이션 시작")
            .setMessage(
                "${lot.name}\n\n" +
                "목적지로 경로 안내를 시작합니다.\n" +
                "도착 전 차량 정보가 주차장에 자동으로 등록됩니다."
            )
            .setPositiveButton("시작") { dialog, _ ->
                dialog.dismiss()

                // 내비게이션 상태 업데이트
                navigatingLotId = lot.id
                populateParkingLots()

                // NaviHelper SDK 호출
                NaviHelper.setDestination(
                    context  = this,
                    lat      = lot.lat,
                    lng      = lot.lng,
                    lotName  = lot.name,
                    lotId    = lot.id
                )

                // 사전 알림 (지오펜스가 아직 감지 안 한 경우 수동 트리거)
                val plate = ParkingStateManager.getPlateNumber(this)
                val token = ParkingStateManager.getAccessToken(this)
                if (plate != null && token != null) {
                    val vin = VehicleDataManager.readVin(this)
                    Thread {
                        runCatching {
                            ApiManager.sendPreNotification(vin, plate, lot.id, "NAVI", token)
                            Log.d(TAG, "내비 목적지 기반 사전 알림 전송 완료: ${lot.id}")
                        }.onFailure {
                            Log.e(TAG, "사전 알림 실패: ${it.message}")
                        }
                    }.start()
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
                dialog.dismiss()
                NaviHelper.cancelNavigation()
                navigatingLotId = null
                populateParkingLots()
                Toast.makeText(this, "경로 안내가 취소되었습니다", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("계속 안내") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 서비스 & 리스너 시작
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * CarPayInService를 시작하고 UI 업데이트 콜백을 등록합니다.
     * MQTT / Geofence / 요금 polling / 토큰 갱신은 서비스가 담당합니다.
     */
    private fun startServicesAndListeners() {
        CarPayInService.start(this)
        registerServiceCallbacks()

        // 서비스 연결 후 2초 뒤 dot 상태 갱신
        handler.postDelayed({
            tvStatusDot.setTextColor(
                if (MqttManager.isConnected()) 0xFF00FF88.toInt() else 0xFF888888.toInt()
            )
        }, 2_000)
    }

    /**
     * CarPayInService의 UI 콜백을 등록합니다.
     * onResume()에서 재등록하여 Activity 재생성 후에도 정상 동작합니다.
     */
    private fun registerServiceCallbacks() {
        // 서비스가 60초마다 polling한 요금 → 실시간 요금 표시 갱신
        CarPayInService.onFeeUpdated = { lotName, amount, _ ->
            tvActiveLotName.text = lotName
            tvParkingEstFee.text = "%,d원".format(amount)
            lastAmount = amount
        }

        // 입차 확정 (MQTT) → UI 상태 전환
        CarPayInService.onParkingConfirmed = { lotId, _ ->
            parkingStartMs = System.currentTimeMillis()
            showParkingActiveSection(lotId)
            tvPaymentStatus.text = "주차 중 — 지금 정산하기 가능"
            tvStatusDot.setTextColor(0xFFFFD700.toInt())
            updateParkingBadge(true)
            showEntryConfirmed(lotId)
        }

        // 결제 완료 (MQTT) → UI 상태 전환 + 내역 갱신
        CarPayInService.onPaymentComplete = { txId, approvalNo, lotId, amount ->
            hideParkingActiveSection()
            tvPaymentStatus.text = "주차장 접근 시 자동 결제됩니다"
            tvStatusDot.setTextColor(0xFF00FF88.toInt())
            updateParkingBadge(false)
            refreshTransactionHistory()
            showPaymentComplete(txId, approvalNo, lotId, amount)
        }

        // MQTT 연결 상태 변경 → dot 색상
        CarPayInService.onConnectionChanged = { connected ->
            tvStatusDot.setTextColor(
                if (connected) 0xFF00FF88.toInt() else 0xFF888888.toInt()
            )
        }

        // 지오펜스 접근 감지 → 해당 주차장 목록 맨 위로 이동 + 강조 표시
        CarPayInService.onLotApproaching = { lotId, lotName ->
            approachingLotId = lotId
            populateParkingLots()
            Log.d(TAG, "주차장 접근 감지 → 목록 상단 표시: $lotName ($lotId)")
        }

        // NaviHelper → 목적지 도착 시 내비게이션 상태 해제
        NaviHelper.onNavigationEnded = {
            navigatingLotId = null
            populateParkingLots()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 입/출차 결제 흐름 UI
    // ─────────────────────────────────────────────────────────────────────────

    private fun showEntryConfirmed(lotId: String) {
        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert)
            .setTitle("🅿 입차 확인")
            .setMessage("$lotId\n\n입차가 확인되었습니다.\n시동을 켜거나 [지금 정산하기] 버튼으로 정산할 수 있습니다.")
            .setPositiveButton("확인") { dialog, _ -> dialog.dismiss() }
            .setCancelable(false)
            .show()
    }

    private fun queryFeeAndShowSettlement() {
        val lotId     = ParkingStateManager.getLotId(this)
        val sessionId = ParkingStateManager.getSessionId(this)
        val token     = ParkingStateManager.getAccessToken(this) ?: return

        tvPaymentStatus.text = "요금 조회 중..."
        tvStatusDot.setTextColor(0xFFFFD700.toInt())
        btnSettleNow.isEnabled = false

        Thread {
            try {
                val fee = ApiManager.queryFee(lotId, sessionId, token)
                handler.post {
                    btnSettleNow.isEnabled = true
                    showSettlementDialog(fee, sessionId)
                }
            } catch (e: Exception) {
                handler.post {
                    tvPaymentStatus.text = "요금 조회 실패"
                    tvStatusDot.setTextColor(0xFFFF4444.toInt())
                    btnSettleNow.isEnabled = true
                }
            }
        }.start()
    }

    private fun showSettlementDialog(fee: ApiManager.FeeResult, sessionId: String) {
        lastAmount = fee.amount
        val h = fee.durationMinutes / 60
        val m = fee.durationMinutes % 60
        val dur = if (h > 0) "${h}시간 ${m}분" else "${m}분"

        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert)
            .setTitle("🅿 정산 확인")
            .setMessage(
                "${fee.lotName}\n\n" +
                        "주차 시간: $dur\n" +
                        "결제 금액: ${"%,d".format(fee.amount)}원\n\n" +
                        "정산하시겠습니까?"
            )
            .setPositiveButton("예") { dialog, _ ->
                dialog.dismiss()
                processPayment(sessionId, fee.amount)
            }
            .setNegativeButton("취소") { dialog, _ ->
                dialog.dismiss()
                tvPaymentStatus.text = "취소됨 — 출구에서 현장 정산"
                tvStatusDot.setTextColor(0xFFFF4444.toInt())
                btnSettleNow.isEnabled = true
            }
            .setCancelable(false)
            .show()
    }

    private fun processPayment(sessionId: String, amount: Int) {
        tvPaymentStatus.text = "결제 처리 중..."
        tvStatusDot.setTextColor(0xFFFFD700.toInt())
        btnSettleNow.isEnabled = false

        val token = ParkingStateManager.getAccessToken(this) ?: return

        Thread {
            try {
                val result = ApiManager.requestPayment(sessionId, amount, token)
                val lotId  = ParkingStateManager.getLotId(this)

                TransactionStore.save(this, result.transactionId, lotId, amount)
                ParkingStateManager.saveParkingState(this, false)

                handler.post {
                    hideParkingActiveSection()
                    tvPaymentStatus.text = "주차장 접근 시 자동 결제됩니다"
                    tvStatusDot.setTextColor(0xFF00FF88.toInt())
                    updateParkingBadge(false)
                    refreshTransactionHistory()
                    showPaymentComplete(result.transactionId, result.approvalNumber, lotId, amount)
                }
            } catch (e: Exception) {
                handler.post {
                    btnSettleNow.isEnabled = true
                    showPaymentError("결제 오류: ${e.message}")
                }
            }
        }.start()
    }

    private fun showPaymentComplete(txId: String, approvalNo: String, lotId: String, amount: Int) {
        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert)
            .setTitle("✓ 결제 완료")
            .setMessage(
                "$lotId\n\n" +
                        "결제 금액: ${"%,d".format(amount)}원\n" +
                        "승인번호: $approvalNo\n" +
                        "거래번호: ${txId.take(14)}…\n\n" +
                        "약 3~5초 후 차단기가 개방됩니다."
            )
            .setPositiveButton("확인") { dialog, _ -> dialog.dismiss() }
            .setCancelable(false)
            .show()
    }

    private fun showPaymentError(message: String) {
        tvPaymentStatus.text = "결제 실패"
        tvStatusDot.setTextColor(0xFFFF4444.toInt())
        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert)
            .setTitle("⚠ 결제 실패")
            .setMessage("$message\n\n다른 결제 수단을 사용하거나 현장 무인정산기를 이용해 주세요.")
            .setPositiveButton("재시도") { _, _ -> queryFeeAndShowSettlement() }
            .setNegativeButton("현장 정산") { dialog, _ -> dialog.dismiss() }
            .setCancelable(false)
            .show()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 거래 내역 UI 갱신
    // ─────────────────────────────────────────────────────────────────────────

    private fun refreshTransactionHistory() {
        val container = findViewById<LinearLayout>(R.id.layoutTxHistory)
        container.removeAllViews()

        val transactions = TransactionStore.load(this)

        // ── 이달 통계 배너 ────────────────────────────────────────────────────
        val cal = java.util.Calendar.getInstance()
        val thisYear  = cal.get(java.util.Calendar.YEAR)
        val thisMonth = cal.get(java.util.Calendar.MONTH)
        val monthlyTxs = transactions.filter {
            val c = java.util.Calendar.getInstance().also { c -> c.timeInMillis = it.timestamp }
            c.get(java.util.Calendar.YEAR) == thisYear &&
            c.get(java.util.Calendar.MONTH) == thisMonth
        }
        val monthlyTotal = monthlyTxs.sumOf { it.amount }
        val monthlyCount = monthlyTxs.size
        val monthLabel   = "${thisMonth + 1}월 주차비"

        val statsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 14, 12, 14)
            setBackgroundColor(0xFF0D1A2A.toInt())
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { lp -> lp.setMargins(0, 0, 0, 16) }
            layoutParams = lp
        }
        statsRow.addView(TextView(this).apply {
            text = monthLabel
            setTextColor(0xFF778899.toInt())
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f)
        })
        statsRow.addView(TextView(this).apply {
            text = "${monthlyCount}건"
            setTextColor(0xFF556677.toInt())
            textSize = 11f
            gravity = android.view.Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        statsRow.addView(TextView(this).apply {
            text = "%,d원".format(monthlyTotal)
            setTextColor(0xFF00E87A.toInt())
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f)
        })
        container.addView(statsRow)

        // ── 거래 내역 없음 ───────────────────────────────────────────────────
        if (transactions.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "결제 내역이 없습니다"
                setTextColor(0xFF555555.toInt())
                textSize = 11f
                gravity = android.view.Gravity.CENTER
                setPadding(0, 16, 0, 16)
            })
            return
        }

        // ── 전체 거래 내역 (최대 20건, 최신순) ────────────────────────────────
        transactions.forEach { tx ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 9, 0, 9)
            }
            row.addView(TextView(this).apply {
                text = TransactionStore.formatDate(tx.timestamp)
                setTextColor(0xFF445566.toInt())
                textSize = 10f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
            })
            row.addView(TextView(this).apply {
                text = tx.lotId
                setTextColor(0xFF778899.toInt())
                textSize = 10f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f)
            })
            row.addView(TextView(this).apply {
                text = TransactionStore.formatAmount(tx.amount)
                setTextColor(0xFF00FF88.toInt())
                textSize = 11f
                gravity = android.view.Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            container.addView(row)
            container.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).also { it.setMargins(0, 2, 0, 2) }
                setBackgroundColor(0xFF1A1A1A.toInt())
            })
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Activity 결과 처리 & 초기화(등록 해제) 공통 함수
    // ─────────────────────────────────────────────────────────────────────────

    @Deprecated("Deprecated in API level 29")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            100 -> when (resultCode) {
                // RegistrationActivity (QR + 카드 등록) 완료
                RESULT_OK -> {
                    showRegisteredState()
                    startServicesAndListeners()
                }
                RESULT_CANCELED -> showUnregisteredState()
            }
            101 -> when (resultCode) {
                // launchCardRegistrationOnly() — 재진입 카드 등록 완료
                RESULT_OK -> {
                    ParkingStateManager.setRegistered(this, true)
                    ParkingStateManager.setOAuthComplete(this, false)
                    showRegisteredState()
                    startServicesAndListeners()
                }
                RESULT_CANCELED -> {
                    // 카드 등록 취소 → OAuth 완료 상태 유지, 버튼 화면
                    showUnregisteredState()
                    // 다음 재진입 시 다시 카드 등록 화면 뜨게 OAuth 플래그는 그대로
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (ParkingStateManager.isRegistered(this)) {
            registerServiceCallbacks()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(timerRunnable)
        VehicleDataManager.release()
    }

    // ── 개발자 트리거 (헤더 5번 탭 → PIN → 디버그 메뉴) ─────────────────────

    private fun setupDevTrigger() {
        tvHeaderTitle.setOnClickListener {
            handler.removeCallbacks(devTapResetRunnable)
            devTapCount++
            if (devTapCount >= DEV_TAP_TARGET) {
                devTapCount = 0
                showDevPinDialog()
            } else {
                handler.postDelayed(devTapResetRunnable, DEV_TAP_WINDOW_MS)
            }
        }
    }

    private fun showDevPinDialog() {
        val et = EditText(this).apply { hint = "PIN 입력"; inputType = 18 }
        androidx.appcompat.app.AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert)
            .setTitle("🔧 개발자 모드")
            .setView(et)
            .setPositiveButton("확인") { _, _ ->
                if (et.text.toString() == DEV_PIN) showDevMenu()
                else Toast.makeText(this, "PIN 오류", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showDevMenu() {
        val items = arrayOf(
            "Mock 입차 확정",
            "Mock 결제 완료",
            "등록 초기화 (즉시)",
            "MQTT 재연결",
            "VIN 표시"
        )
        androidx.appcompat.app.AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert)
            .setTitle("개발자 메뉴")
            .setItems(items) { _, idx ->
                when (idx) {
                    0 -> {
                        ParkingStateManager.saveParkingState(this, true, "DEV_LOT_01", "sess_dev_001")
                        showRegisteredState()
                        Toast.makeText(this, "Mock 입차 확정 완료", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        ParkingStateManager.saveParkingState(this, false)
                        showRegisteredState()
                        Toast.makeText(this, "Mock 결제 완료", Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        ParkingStateManager.setRegistered(this, false)
                        recreate()
                    }
                    3 -> {
                        Thread { MqttManager.connect(vin) }.start()
                        Toast.makeText(this, "MQTT 재연결 시도", Toast.LENGTH_SHORT).show()
                    }
                    4 -> Toast.makeText(this, "VIN: $vin", Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    // ── 등록 초기화 ───────────────────────────────────────────────────────────

    private fun confirmReset() {
        androidx.appcompat.app.AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert)
            .setTitle("⚠ 등록 해제")
            .setMessage("등록된 카드와 차량 정보를 모두 삭제합니다.\n계속하시겠습니까?")
            .setPositiveButton("삭제") { _, _ ->
                CarPayInService.stop(this)
                ParkingStateManager.setRegistered(this, false)
                ParkingStateManager.saveParkingState(this, false)
                recreate()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ── VIN 마스킹 유틸 ───────────────────────────────────────────────────────

    private fun maskVin(vin: String): String {
        return if (vin.length >= 6) "VIN: ${vin.take(3)}•••${vin.takeLast(3)}" else "VIN: $vin"
    }
}
