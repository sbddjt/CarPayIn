package com.example.carpayin

import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
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

class MainActivity : AppCompatActivity() {

    private val TAG = "CarPayIn"
    private val handler = Handler(Looper.getMainLooper())

    // ── VIN ──────────────────────────────────────────────────────────────────
    private var vin: String = ""

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

        if (ParkingStateManager.isRegistered(this)) {
            showRegisteredState()
            startServicesAndListeners()
        } else {
            showUnregisteredState()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 상태 전환
    // ─────────────────────────────────────────────────────────────────────────

    private fun showUnregisteredState() {
        layoutUnregistered.visibility = View.VISIBLE
        layoutRegistered.visibility   = View.GONE
        btnResetApp.visibility        = View.GONE // 미등록일 땐 초기화 버튼 숨김
        tvStatusDot.setTextColor(0xFF333333.toInt())

        findViewById<Button>(R.id.btnRegister).setOnClickListener {
            startActivityForResult(Intent(this, RegistrationActivity::class.java), 100)
        }
    }

    private fun showRegisteredState() {
        layoutUnregistered.visibility = View.GONE
        layoutRegistered.visibility   = View.VISIBLE
        btnResetApp.visibility        = View.VISIBLE // 등록 상태에서 초기화 버튼 표시

        tvVinShort.text = maskVin(vin)
        tvPlateNumber.text = ParkingStateManager.getPlateNumber(this) ?: "—"

        // 등록된 카드 UI 테마 적용
        val brand    = ParkingStateManager.getCardBrand(this)
        val lastFour = ParkingStateManager.getCardLastFour(this)
        val theme    = getCardBrandTheme(brand)

        mainCardBody.setBackgroundColor(theme.bgColor)
        mainCardBrand.text = theme.shortName
        mainCardBrand.setTextColor(theme.brandTextColor)
        mainCardNetwork.text = theme.network
        mainCardNumber.text = "•••• •••• •••• $lastFour"

        // 카드 변경
        findViewById<Button>(R.id.btnChangeCard).setOnClickListener {
            AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert)
                .setTitle("카드 변경")
                .setMessage("등록된 카드를 변경하시겠습니까?")
                .setPositiveButton("변경하기") { _, _ ->
                    val intent = Intent(this, RegistrationActivity::class.java)
                    intent.putExtra("reregister", true)
                    startActivityForResult(intent, 100)
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

        GeofenceManager.cachedParkingLots.forEach { lot ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(16, 14, 16, 14)
                val bg = getDrawable(R.drawable.bg_card_dark)
                background = bg
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, 0, 0, 8)
                layoutParams = lp
            }

            val tvIcon = TextView(this).apply { text = "📍"; textSize = 16f }
            val tvInfo = TextView(this).apply {
                text = lot.name
                setTextColor(Color.WHITE)
                textSize = 13f
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                lp.setMargins(10, 0, 0, 0)
                layoutParams = lp
            }
            val tvStatus = TextView(this).apply {
                text = "자동 감지"
                setTextColor(Color.parseColor("#00AA55"))
                textSize = 11f
            }

            row.addView(tvIcon)
            row.addView(tvInfo)
            row.addView(tvStatus)
            layoutParkingLots.addView(row)
        }
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
