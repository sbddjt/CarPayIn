package com.example.carpayin

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import java.util.UUID

class RegistrationActivity : Activity() {

    private val TAG = "RegistrationActivity"
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_registration)

        val vin = intent.getStringExtra("vin") ?: ""
        val isReregister = intent.getBooleanExtra("reregister", false)
        findViewById<TextView>(R.id.tvRegVin).text = "VIN: ${vin.take(5)}•••••••••••"

        // ECDSA 키쌍 생성
        Thread {
            try {
                KeystoreManager.generateKeyPairIfNeeded()
                Log.d(TAG, "공개키 준비 완료")
                handler.post {
                    findViewById<TextView>(R.id.tvKeyStatus).apply {
                        text = "🔑 HSM 보안키 준비 완료"
                        setTextColor(0xFF00AA55.toInt())
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "키 생성 실패: ${e.message}")
                handler.post {
                    findViewById<TextView>(R.id.tvKeyStatus).apply {
                        text = "⚠ 보안키 생성 실패"
                        setTextColor(0xFFFF4444.toInt())
                    }
                }
            }
        }.start()

        // 카드 실시간 프리뷰
        val tvPreviewNumber = findViewById<TextView>(R.id.tvCardPreviewNumber)
        val tvPreviewExpiry = findViewById<TextView>(R.id.tvCardPreviewExpiry)
        val etCardNumber = findViewById<EditText>(R.id.etCardNumber)
        val etCardExpiry = findViewById<EditText>(R.id.etCardExpiry)
        val etCardCvc = findViewById<EditText>(R.id.etCardCvc)

        etCardNumber.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val raw = s?.toString() ?: ""
                val padded = raw.padEnd(16, '•')
                tvPreviewNumber.text = buildString {
                    append(padded.substring(0, 4)); append(" ")
                    append(padded.substring(4, 8)); append(" ")
                    append(padded.substring(8, 12)); append(" ")
                    append(padded.substring(12, 16))
                }
                // 4자리마다 자동 포맷 (실제 입력값은 숫자만)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        etCardExpiry.addTextChangedListener(object : TextWatcher {
            private var isFormatting = false
            override fun afterTextChanged(s: Editable?) {
                if (isFormatting) return
                isFormatting = true
                val raw = s?.toString()?.replace("/", "") ?: ""
                if (raw.length >= 2) {
                    val formatted = raw.substring(0, 2) + "/" + raw.substring(2)
                    s?.replace(0, s.length, formatted)
                }
                tvPreviewExpiry.text = if (s.isNullOrEmpty()) "MM/YY" else s.toString()
                isFormatting = false
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // 뒤로 가기
        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        // 등록 버튼
        val btnRegister = findViewById<Button>(R.id.btnRegister)
        btnRegister.setOnClickListener {
            val cardNumber = etCardNumber.text.toString().trim()
            val cardExpiry = etCardExpiry.text.toString().trim()
            val cardCvc = etCardCvc.text.toString().trim()

            // 유효성 검사
            if (cardNumber.length < 16) {
                shake(etCardNumber)
                Toast.makeText(this, "카드번호 16자리를 입력하세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!cardExpiry.matches(Regex("\\d{2}/\\d{2}"))) {
                shake(etCardExpiry)
                Toast.makeText(this, "유효기간을 MM/YY 형식으로 입력하세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (cardCvc.length < 3) {
                shake(etCardCvc)
                Toast.makeText(this, "CVC 3자리를 입력하세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            performRegistration(vin, cardNumber, cardExpiry, cardCvc, btnRegister)
        }
    }

    private fun performRegistration(
        vin: String,
        cardNumber: String,
        cardExpiry: String,
        cardCvc: String,
        btnRegister: Button
    ) {
        btnRegister.isEnabled = false
        btnRegister.text = "등록 중..."

        Thread {
            try {
                // 공개키 가져오기 (ECDSA P-256)
                val publicKeyPem = KeystoreManager.getPublicKeyPem()

                // ── 실제 구현: POST /v1/register to 오토에버 Private Server ──
                // val body = JSONObject().apply {
                //     put("vin", vin)
                //     put("card_number", encryptAesGcm(cardNumber))
                //     put("card_expiry", cardExpiry)
                //     put("card_cvc", encryptAesGcm(cardCvc))
                //     put("public_key_pem", publicKeyPem)
                // }
                // val response = HttpClient.post("https://autoever-private/v1/register", body)
                // val uuid = response.getString("uuid")
                // ──────────────────────────────────────────────────────────────

                // Mock: 로컬 UUID (TODO: 오토에버 서버 연동 후 교체)
                val uuid = UUID.randomUUID().toString()
                Log.d(TAG, "등록 완료 — UUID: $uuid, PublicKey: ${publicKeyPem.take(40)}...")

                handler.post {
                    getSharedPreferences("carpayin", MODE_PRIVATE).edit()
                        .putBoolean("registered", true)
                        .putString("uuid", uuid)
                        .apply()

                    Toast.makeText(this, "현대카드 등록 완료!", Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK)
                    finish()
                }
            } catch (e: Exception) {
                Log.e(TAG, "등록 실패: ${e.message}")
                handler.post {
                    btnRegister.isEnabled = true
                    btnRegister.text = "등록하기"
                    Toast.makeText(this, "등록 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun shake(view: View) {
        // 간단한 시각적 피드백
        view.animate()
            .translationX(8f).setDuration(50)
            .withEndAction {
                view.animate().translationX(-8f).setDuration(50)
                    .withEndAction { view.animate().translationX(0f).setDuration(50) }
            }
    }
}
