package com.example.carpayin

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.util.UUID

class RegistrationActivity : Activity() {

    private val TAG = "RegistrationActivity"
    private val handler = Handler(Looper.getMainLooper())
    private var isPolling = false

    // Views
    private lateinit var ivQrCode: ImageView
    private lateinit var tvPollingStatus: TextView
    private lateinit var btnCancel: Button

    // Data
    private lateinit var loginSessionId: String
    private lateinit var vin: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_registration)

        ivQrCode = findViewById(R.id.ivQrCode)
        tvPollingStatus = findViewById(R.id.tvPollingStatus)
        btnCancel = findViewById(R.id.btnCancel)

        vin = VehicleDataManager.readVin(this)

        // 1. 고유 세션 ID 생성
        loginSessionId = UUID.randomUUID().toString()

        btnCancel.setOnClickListener {
            isPolling = false
            setResult(RESULT_CANCELED)
            finish()
        }

        // 2. QR 코드 화면 표시
        showQrCode()

        // 3. 백엔드 폴링 시작 (폰에서 로그인 완료 대기)
        startPollingLoginStatus()
    }

    private fun showQrCode() {
        // 스마트폰이 접속할 백엔드 주소 (FastAPI 서버)
        // 안드로이드 에뮬레이터에서 로컬호스트 접근 시 10.0.2.2 사용
        val authUrl = "http://10.0.2.2:8080/auth/mobile/start?session_id=$loginSessionId&vin=$vin"

        Thread {
            try {
                val writer = QRCodeWriter()
                val bitMatrix = writer.encode(authUrl, BarcodeFormat.QR_CODE, 512, 512)
                val width = bitMatrix.width
                val height = bitMatrix.height
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)

                for (x in 0 until width) {
                    for (y in 0 until height) {
                        bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
                    }
                }

                handler.post {
                    ivQrCode.setImageBitmap(bitmap)
                }
            } catch (e: Exception) {
                Log.e(TAG, "QR 생성 실패", e)
            }
        }.start()
    }

    private fun startPollingLoginStatus() {
        isPolling = true
        val pollRunnable = object : Runnable {
            override fun run() {
                if (!isPolling) return

                Thread {
                    try {
                        // 백엔드에 세션 완료 여부 확인
                        val statusResult = ApiManager.checkLoginSession(loginSessionId)

                        if (statusResult.isComplete) {
                            isPolling = false

                            // 완료 시 토큰 및 차량정보 저장
                            ParkingStateManager.saveTokens(this@RegistrationActivity, statusResult.accessToken, statusResult.refreshToken)
                            ParkingStateManager.savePlateNumber(this@RegistrationActivity, statusResult.plateNumber)
                            ParkingStateManager.setRegistered(this@RegistrationActivity, true)

                            handler.post {
                                Toast.makeText(this@RegistrationActivity, "✓ 차량 연동이 완료되었습니다!", Toast.LENGTH_SHORT).show()
                                setResult(RESULT_OK)
                                finish()
                            }
                        } else {
                            // 대기 중이면 2초 뒤 재시도
                            handler.postDelayed(this, 2000)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "폴링 중 오류 발생, 재시도", e)
                        handler.postDelayed(this, 2000)
                    }
                }.start()
            }
        }
        handler.post(pollRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        isPolling = false
    }
}