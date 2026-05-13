package com.example.carpayin.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
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
import com.example.carpayin.R
import com.example.carpayin.data.ParkingStateManager
import com.example.carpayin.network.ApiManager
import com.example.carpayin.vehicle.VehicleDataManager
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.security.MessageDigest
import java.util.UUID

class RegistrationActivity : Activity() {

    private val TAG = "RegistrationActivity"
    private val handler = Handler(Looper.getMainLooper())
    private var isPolling = false

    private lateinit var ivQrCode: ImageView
    private lateinit var tvPollingStatus: TextView
    private lateinit var tvSubMessage: TextView
    private lateinit var btnCancel: Button
    private lateinit var btnRefreshQr: Button

    private lateinit var loginSessionId: String
    private lateinit var vin: String

    private val POLL_TIMEOUT_MS = 5 * 60 * 1000L
    private var pollStartTime = 0L
    private var didCompleteLogin = false
    private var didHideQrAfterAuth = false
    private var pollCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_registration)

        ivQrCode = findViewById(R.id.ivQrCode)
        tvPollingStatus = findViewById(R.id.tvPollingStatus)
        tvSubMessage = findViewById(R.id.tvSubMessage)
        btnCancel = findViewById(R.id.btnCancel)
        btnRefreshQr = findViewById(R.id.btnRefreshQr)

        vin = VehicleDataManager.readVin(this)
        loginSessionId = UUID.randomUUID().toString()

        btnCancel.setOnClickListener {
            isPolling = false
            setResult(RESULT_CANCELED)
            finish()
        }

        btnRefreshQr.setOnClickListener {
            isPolling = false
            handler.removeCallbacksAndMessages(null)
            loginSessionId = UUID.randomUUID().toString()
            ivQrCode.visibility = View.VISIBLE
            btnRefreshQr.visibility = View.VISIBLE
            btnCancel.visibility = View.VISIBLE
            ivQrCode.setImageBitmap(null)
            tvPollingStatus.text = "Scan this QR with MyHyundai"
            tvSubMessage.text = "Log in with your MyHyundai account to link a vehicle."
            renderQrCode()
            startPolling()
        }

        renderQrCode()
        startPolling()
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun renderQrCode() {
        val vinHash = sha256(vin + loginSessionId)
        val authStartUrl = "${ApiManager.QR_BASE_URL}/auth/hyundai/start?session_id=$loginSessionId&vin_hash=$vinHash"

        Thread {
            try {
                val bits = QRCodeWriter().encode(authStartUrl, BarcodeFormat.QR_CODE, 512, 512)
                val bitmap = Bitmap.createBitmap(bits.width, bits.height, Bitmap.Config.RGB_565)
                for (x in 0 until bits.width) {
                    for (y in 0 until bits.height) {
                        bitmap.setPixel(x, y, if (bits[x, y]) Color.BLACK else Color.WHITE)
                    }
                }
                handler.post { ivQrCode.setImageBitmap(bitmap) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to render QR: ${e.message}")
            }
        }.start()
    }

    private fun startPolling() {
        isPolling = true
        didCompleteLogin = false
        didHideQrAfterAuth = false
        pollCount = 0
        pollStartTime = System.currentTimeMillis()
        scheduleNextPoll()
    }

    private fun scheduleNextPoll() {
        if (!isPolling || didCompleteLogin) return
        handler.postDelayed({ doPoll() }, 2_000)
    }

    private fun doPoll() {
        if (!isPolling || didCompleteLogin) return
        if (System.currentTimeMillis() - pollStartTime > POLL_TIMEOUT_MS) {
            isPolling = false
            tvPollingStatus.text = "Login timed out"
            tvSubMessage.text = "Refresh the QR and try again."
            return
        }

        Thread {
            try {
                val result = ApiManager.checkLoginSession(loginSessionId)
                if (result.isComplete) {
                    handler.post { onLoginComplete(result) }
                } else if (result.status == "authorized" || result.status == "agreement_required") {
                    handler.post {
                        showAuthorizedProgress(result.status)
                        scheduleNextPoll()
                    }
                } else if (result.status == "error" || result.status == "agreement_error") {
                    handler.post {
                        isPolling = false
                        showAuthorizedProgress(result.status)
                        tvPollingStatus.text = "MyHyundai processing failed"
                        tvSubMessage.text = result.debugMessage.ifBlank { "Refresh the QR and try again." }
                        btnRefreshQr.visibility = View.VISIBLE
                        btnCancel.visibility = View.VISIBLE
                    }
                } else {
                    handler.post {
                        pollCount += 1
                        tvPollingStatus.text = "Waiting for login... ($pollCount)"
                        if (pollCount >= 2 && result.debugMessage.isNotBlank()) {
                            tvSubMessage.text = result.debugMessage
                        }
                        scheduleNextPoll()
                    }
                }
            } catch (e: Exception) {
                handler.post {
                    pollCount += 1
                    tvPollingStatus.text = "Checking login status..."
                    tvSubMessage.text = e.message ?: "Failed to check login status"
                    scheduleNextPoll()
                }
            }
        }.start()
    }

    private fun onLoginComplete(result: ApiManager.SessionStatusResult) {
        if (didCompleteLogin) return
        didCompleteLogin = true
        isPolling = false
        handler.removeCallbacksAndMessages(null)

        ivQrCode.visibility = View.GONE
        btnCancel.visibility = View.GONE
        btnRefreshQr.visibility = View.GONE
        tvPollingStatus.text = "MyHyundai login complete"
        tvSubMessage.text = "Linking the vehicle selected in MyHyundai."

        val linkedVehicles = result.vehicleList.filter { it.carId.isNotBlank() }
        when {
            linkedVehicles.isEmpty() -> {
                didCompleteLogin = false
                btnRefreshQr.visibility = View.VISIBLE
                btnCancel.visibility = View.VISIBLE
                tvPollingStatus.text = "No Hyundai vehicle found"
                tvSubMessage.text = "No linked vehicle was returned. Check the backend vehicle-list log."
            }
            linkedVehicles.size == 1 -> {
                completeRegistration(result, linkedVehicles.first())
            }
            else -> {
                showVehiclePicker(result, linkedVehicles)
            }
        }
    }

    private fun showAuthorizedProgress(status: String = "authorized") {
        if (!didHideQrAfterAuth) {
            didHideQrAfterAuth = true
            ivQrCode.visibility = View.GONE
            btnRefreshQr.visibility = View.GONE
            btnCancel.visibility = View.GONE
        }
        tvPollingStatus.text = "MyHyundai login complete"
        tvSubMessage.text = if (status == "agreement_required") {
            "Waiting for Hyundai data agreement..."
        } else {
            "Fetching Hyundai vehicle list..."
        }
    }

    private fun showVehiclePicker(
        result: ApiManager.SessionStatusResult,
        vehicles: List<ApiManager.VehicleInfo>
    ) {
        val labels = vehicles.map { vehicleLabel(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("CarPayIn에 연결할 차량")
            .setItems(labels) { _, which ->
                completeRegistration(result, vehicles[which])
            }
            .setOnCancelListener {
                didCompleteLogin = false
                btnRefreshQr.visibility = View.VISIBLE
                btnCancel.visibility = View.VISIBLE
                tvPollingStatus.text = "Vehicle selection required"
                tvSubMessage.text = "Choose the Hyundai vehicle to link with this car."
            }
            .show()
    }

    private fun vehicleLabel(vehicle: ApiManager.VehicleInfo): String {
        val model = vehicle.modelName.ifBlank { "Hyundai vehicle" }
        val year = if (vehicle.year > 0) " (${vehicle.year})" else ""
        val idTail = vehicle.carId.takeLast(6)
        return "$model$year - carId ...$idTail"
    }

    private fun completeRegistration(
        result: ApiManager.SessionStatusResult,
        selectedVehicle: ApiManager.VehicleInfo?
    ) {
        val selectedModel = selectedVehicle?.modelName?.ifBlank { result.modelName } ?: result.modelName
        val selectedCarId = selectedVehicle?.carId.orEmpty()

        runCatching {
            if (result.accessToken.isNotBlank() && result.refreshToken.isNotBlank()) {
                ParkingStateManager.saveTokens(this, result.accessToken, result.refreshToken)
            }
            if (result.plateNumber.isNotBlank()) {
                ParkingStateManager.savePlateNumber(this, result.plateNumber)
            }
            ParkingStateManager.saveHyundaiUserInfo(this, result.userId, result.userName, selectedModel, selectedCarId)
            ParkingStateManager.setOAuthComplete(this, true)
            ParkingStateManager.setRegistered(this, false)
        }.onFailure {
            Log.e(TAG, "Failed to save login state", it)
            ParkingStateManager.setOAuthComplete(this, true)
            ParkingStateManager.setRegistered(this, false)
        }

        if (selectedCarId.isNotEmpty() && result.accessToken.isNotBlank()) {
            Thread {
                runCatching {
                    ApiManager.confirmCar(
                        vinHash = sha256(vin + loginSessionId),
                        carId = selectedCarId,
                        accessToken = result.accessToken
                    )
                }.onFailure {
                    Log.w(TAG, "Failed to confirm Hyundai vehicle link: ${it.message}")
                }
            }.start()
        }

        handler.postDelayed({
            startActivity(Intent(this, MainActivity::class.java).apply {
                action = MainActivity.ACTION_SHOW_OAUTH_PENDING
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra(MainActivity.EXTRA_SHOW_OAUTH_PENDING, true)
            })
            finish()
        }, 800)
    }

    override fun onDestroy() {
        super.onDestroy()
        isPolling = false
        handler.removeCallbacksAndMessages(null)
    }
}
