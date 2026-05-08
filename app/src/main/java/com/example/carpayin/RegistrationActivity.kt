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
    private lateinit var tvInstruction: TextView
    private lateinit var btnCancel: Button

    private lateinit var loginSessionId: String
    private lateinit var vin: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // TODO: activity_registration.xml에 ImageView(R.id.ivQrCode)를 추가하고 WebView는 삭제하세요.
        setContentView(R.layout.activity_registration)

        ivQrCode = findViewById(R.id.ivQrCode) // QR이 표시될 이미지 뷰
        tvInstruction = findViewById(R.id.tvRegDetail)
        btnCancel = findViewById(R.id.btnWebViewCancel)

        vin = VehicleDataManager.readVin(this)

        // 1. 고유 세션 ID 생성
        loginSessionId = UUID.randomUUID().toString()

        btnCancel.setOnClickListener {
            isPolling = false
            setResult(RESULT_CANCELED)
            finish()
        }

        // 2. QR 코드 생성 및 화면 표시
        showQrCode()

        // 3. 백엔드 폴링 시작 (폰에서 로그인 완료할 때까지 대기)
        startPollingLoginStatus()
    }

    private fun showQrCode() {
        // 폰으로 스캔 시 접속할 서버 주소 (또는 딥링크)
        // 백엔드가 이 URL을 받아서 마이현대 로그인 창으로 리다이렉트 시켜줘야 합니다.
        val authUrl = "http://10.0.2.2:8080/auth/mobile/start?session_id=$loginSessionId&vin=$vin"

        tvInstruction.text = "스마트폰 카메라로 QR 코드를 스캔하여\n마이현대 로그인을 진행해 주세요."

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
                    ivQrCode.visibility = View.VISIBLE
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
                        // 백엔드에 세션 상태 확인 요청 (ApiManager에 새로 추가 필요)
                        val statusResult = ApiManager.checkLoginSession(loginSessionId)

                        if (statusResult.isComplete) {
                            isPolling = false
                            // 인증 완료! 정보 저장
                            ParkingStateManager.saveTokens(this@RegistrationActivity, statusResult.accessToken, statusResult.refreshToken)
                            ParkingStateManager.savePlateNumber(this@RegistrationActivity, statusResult.plateNumber)
                            ParkingStateManager.setRegistered(this@RegistrationActivity, true)

                            handler.post {
                                Toast.makeText(this@RegistrationActivity, "✓ 차량 등록 완료!", Toast.LENGTH_SHORT).show()
                                setResult(RESULT_OK)
                                finish()
                            }
                        } else {
                            // 아직 폰에서 로그인 중이므로 2초 뒤 다시 시도
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