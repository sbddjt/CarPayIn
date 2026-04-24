package com.example.carpayin

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import android.widget.Toast

class MainActivity : Activity() {

    private val TAG = "CarPayIn"
    private lateinit var vin: String
    private lateinit var uuid: String
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var tvStatusDot: TextView
    private lateinit var tvPaymentStatus: TextView
    private lateinit var tvVinShort: TextView
    private lateinit var layoutUnregistered: LinearLayout
    private lateinit var layoutRegistered: LinearLayout
    private lateinit var tvHeaderTitle: LinearLayout

    // 현재 주차 중인 lot_id (출차 결제 시 거래내역 저장에 사용)
    private var currentLotId: String = ""
    private var lastAmount: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatusDot      = findViewById(R.id.tvStatusDot)
        tvPaymentStatus  = findViewById(R.id.tvPaymentStatus)
        tvVinShort       = findViewById(R.id.tvVinShort)
        layoutUnregistered = findViewById(R.id.layoutUnregistered)
        layoutRegistered   = findViewById(R.id.layoutRegistered)
        tvHeaderTitle    = findViewById<LinearLayout>(R.id.tvHeaderTitle)

        vin = getOrCreateVin()

        val prefs = getSharedPreferences("carpayin", MODE_PRIVATE)
        val isRegistered = prefs.getBoolean("registered", false)
        uuid = prefs.getString("uuid", "") ?: ""

        // 헤더 5초 꾹 누르기 → PIN 입력 → 개발자 메뉴
        setupDevTrigger(tvHeaderTitle)

        if (isRegistered) {
            showRegisteredState()
            startBleAndMqtt()
        } else {
            showUnregisteredState()
        }
    }

    // ── 상태 전환 ─────────────────────────────────────────────────────────────

    private fun showUnregisteredState() {
        layoutUnregistered.visibility = View.VISIBLE
        layoutRegistered.visibility   = View.GONE
        tvStatusDot.setTextColor(0xFF333333.toInt())

        findViewById<Button>(R.id.btnRegister).setOnClickListener {
            val intent = Intent(this, RegistrationActivity::class.java)
            intent.putExtra("vin", vin)
            startActivityForResult(intent, 100)
        }
    }

    private fun showRegisteredState() {
        layoutUnregistered.visibility = View.GONE
        layoutRegistered.visibility   = View.VISIBLE
        tvVinShort.text = maskVin(vin)

        // 카드 변경 버튼
        findViewById<Button>(R.id.btnChangeCard).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("카드 변경")
                .setMessage("등록된 현대카드를 변경하시겠습니까?")
                .setPositiveButton("변경하기") { _, _ ->
                    val intent = Intent(this, RegistrationActivity::class.java)
                    intent.putExtra("vin", vin)
                    intent.putExtra("reregister", true)
                    startActivityForResult(intent, 100)
                }
                .setNegativeButton("취소", null)
                .show()
        }

        refreshTransactionHistory()
    }

    // ── 거래 내역 UI 갱신 ────────────────────────────────────────────────────

    private fun refreshTransactionHistory() {
        val container = findViewById<LinearLayout>(R.id.layoutTxHistory)
        container.removeAllViews()

        val transactions = TransactionStore.load(this)
        if (transactions.isEmpty()) {
            val emptyView = TextView(this).apply {
                text = "결제 내역이 없습니다"
                setTextColor(0xFF333333.toInt())
                textSize = 11f
                gravity = android.view.Gravity.CENTER
                setPadding(0, 16, 0, 16)
            }
            container.addView(emptyView)
            return
        }

        transactions.take(5).forEach { tx ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
            }

            val tvDate = TextView(this).apply {
                text = TransactionStore.formatDate(tx.timestamp)
                setTextColor(0xFF444444.toInt())
                textSize = 10f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvLot = TextView(this).apply {
                text = tx.lotId
                setTextColor(0xFF777777.toInt())
                textSize = 10f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvAmount = TextView(this).apply {
                text = TransactionStore.formatAmount(tx.amount)
                setTextColor(0xFF00FF88.toInt())
                textSize = 11f
                gravity = android.view.Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            row.addView(tvDate)
            row.addView(tvLot)
            row.addView(tvAmount)
            container.addView(row)

            // 구분선
            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                    setMargins(0, 2, 0, 2)
                }
                setBackgroundColor(0xFF1A1A1A.toInt())
            }
            container.addView(divider)
        }
    }

    // ── MQTT + BLE 시작 ──────────────────────────────────────────────────────

    private fun startBleAndMqtt() {
        // BLE 초기화 (에뮬레이터에서는 시뮬레이션 모드)
        BleManager.initialize(this)
        BleManager.onParkingLotDetected = { lotId, rssi ->
            handler.post {
                currentLotId = lotId
                showEntryNotification(lotId)
            }
        }
        BleManager.startScan(this)

        // MQTT 콜백
        MqttManager.onEntryNotification = { lotId ->
            handler.post {
                currentLotId = lotId
                showEntryNotification(lotId)
            }
        }
        MqttManager.onPaymentChallenge = { amount, nonce, merchantId, correlationId ->
            handler.post {
                lastAmount = amount
                processPayment(amount, nonce, merchantId, correlationId)
            }
        }
        MqttManager.onPaymentComplete = { transactionId ->
            handler.post {
                TransactionStore.save(this, transactionId, currentLotId, lastAmount)
                showPaymentComplete(transactionId)
            }
        }

        Thread {
            MqttManager.connect(uuid)
            handler.post {
                tvStatusDot.setTextColor(
                    if (MqttManager.isConnected()) 0xFF00FF88.toInt() else 0xFF333333.toInt()
                )
            }
        }.start()
    }

    // ── 결제 플로우 ──────────────────────────────────────────────────────────

    private fun showEntryNotification(lotId: String) {
        tvPaymentStatus.text = "주차 중 — 출차 시 자동 결제"
        tvStatusDot.setTextColor(0xFFFFD700.toInt())
        currentLotId = lotId

        AlertDialog.Builder(this)
            .setTitle("🅿 주차장 입차")
            .setMessage("$lotId\n\n입차가 확인되었습니다.\n출차 시 자동으로 결제됩니다.")
            .setPositiveButton("확인") { dialog, _ ->
                dialog.dismiss()
                tvStatusDot.setTextColor(0xFF00FF88.toInt())
            }
            .setCancelable(false)
            .show()
    }

    private fun processPayment(amount: Int, nonce: String, merchantId: String, correlationId: String) {
        tvPaymentStatus.text = "결제 처리 중..."
        tvStatusDot.setTextColor(0xFFFFD700.toInt())

        Thread {
            try {
                MqttManager.publishArqc(uuid, vin, amount, nonce, correlationId)
                handler.post { tvPaymentStatus.text = "승인 대기 중..." }
            } catch (e: Exception) {
                Log.e(TAG, "ARQC 실패: ${e.message}")
                handler.post {
                    tvPaymentStatus.text = "결제 오류 — 재시도 중..."
                    tvStatusDot.setTextColor(0xFFFF4444.toInt())
                }
            }
        }.start()
    }

    private fun showPaymentComplete(transactionId: String) {
        tvPaymentStatus.text = "주차장 접근 시 자동 결제됩니다"
        tvStatusDot.setTextColor(0xFF00FF88.toInt())
        refreshTransactionHistory()

        AlertDialog.Builder(this)
            .setTitle("✓ 결제 완료")
            .setMessage("주차 요금이 현대카드로\n자동 결제되었습니다.\n\n${currentLotId}\n거래번호: ${transactionId.take(12)}...")
            .setPositiveButton("확인") { dialog, _ -> dialog.dismiss() }
            .setCancelable(false)
            .show()
    }

    // ── Activity 결과 처리 ───────────────────────────────────────────────────

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK) {
            val prefs = getSharedPreferences("carpayin", MODE_PRIVATE)
            uuid = prefs.getString("uuid", "") ?: ""
            showRegisteredState()
            startBleAndMqtt()
        }
    }

    // ── 유틸 ─────────────────────────────────────────────────────────────────

    private fun maskVin(vin: String): String {
        if (vin.length <= 5) return vin
        return vin.take(5) + "•".repeat(vin.length - 5)
    }

    private fun getOrCreateVin(): String {
        val prefs = getSharedPreferences("carpayin", MODE_PRIVATE)
        return prefs.getString("vin", null) ?: run {
            val random = (100000000000L..999999999999L).random()
            val newVin = "KMHXX$random"
            prefs.edit().putString("vin", newVin).apply()
            newVin
        }
    }

    // ── 개발자 진입: 5초 홀드 + PIN 1234 ────────────────────────────────────

    private val devHoldRunnable = Runnable { showDevPinDialog() }
    private val DEV_HOLD_MS = 5000L
    private val DEV_PIN = "1234"

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupDevTrigger(view: LinearLayout) {
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    handler.postDelayed(devHoldRunnable, DEV_HOLD_MS)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(devHoldRunnable)
                }
            }
            false  // 클릭 이벤트 계속 전달
        }
    }

    private fun showDevPinDialog() {
        val etPin = EditText(this).apply {
            hint = "코드 입력"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            textSize = 18f
            gravity = android.view.Gravity.CENTER
            setPadding(40, 24, 40, 24)
        }

        AlertDialog.Builder(this)
            .setTitle("개발자 인증")
            .setMessage("접근 코드를 입력하세요")
            .setView(etPin)
            .setPositiveButton("확인") { dialog, _ ->
                if (etPin.text.toString() == DEV_PIN) {
                    dialog.dismiss()
                    showDevMenu()
                } else {
                    Toast.makeText(this, "코드가 올바르지 않습니다", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ── 개발자 메뉴 ──────────────────────────────────────────────────────────

    private fun showDevMenu() {
        AlertDialog.Builder(this)
            .setTitle("개발자 메뉴")
            .setItems(arrayOf("앱 초기화 (카드 등록 해제)", "거래 내역 삭제", "MQTT 재연결", "VIN 확인")) { _, which ->
                when (which) {
                    0 -> confirmReset()
                    1 -> {
                        TransactionStore.clear(this)
                        refreshTransactionHistory()
                    }
                    2 -> {
                        MqttManager.disconnect()
                        Thread {
                            MqttManager.connect(uuid)
                            handler.post {
                                tvStatusDot.setTextColor(
                                    if (MqttManager.isConnected()) 0xFF00FF88.toInt() else 0xFF333333.toInt()
                                )
                            }
                        }.start()
                    }
                    3 -> AlertDialog.Builder(this)
                        .setTitle("VIN")
                        .setMessage(vin)
                        .setPositiveButton("확인", null)
                        .show()
                }
            }
            .show()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("초기화 확인")
            .setMessage("카드 등록 정보가 삭제됩니다.\n계속하시겠습니까?")
            .setPositiveButton("초기화") { _, _ ->
                getSharedPreferences("carpayin", MODE_PRIVATE).edit()
                    .remove("registered")
                    .remove("uuid")
                    .apply()
                MqttManager.disconnect()
                BleManager.stopScan(this)
                recreate()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        MqttManager.disconnect()
        BleManager.stopScan(this)
    }
}
