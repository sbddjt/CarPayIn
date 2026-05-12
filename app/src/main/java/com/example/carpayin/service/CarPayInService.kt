package com.example.carpayin.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.example.carpayin.data.ParkingStateManager
import com.example.carpayin.data.TransactionStore
import com.example.carpayin.network.ApiManager
import com.example.carpayin.network.MqttManager
import com.example.carpayin.ui.MainActivity
import com.example.carpayin.vehicle.GeofenceManager
import com.example.carpayin.vehicle.VehicleDataManager

/**
 * CarPayIn Foreground Service
 *
 * 앱이 백그라운드에 있거나 완전히 종료된 상태에서도 다음을 처리합니다:
 *  ▸ MQTT 연결 유지 + 워치독 재연결 (30초 간격)
 *  ▸ Geofence 위치 감시 + 사전 알림 자동 전송
 *  ▸ 주차 중 요금 자동 polling (60초 간격)
 *  ▸ 시동 ON 감지 → 자동 요금 조회
 *  ▸ 액세스 토큰 자동 갱신 (만료 5분 전)
 *  ▸ 입차 확정 / 결제 완료 Android Notification
 */
class CarPayInService : Service() {

    private val TAG = "CarPayInService"
    private val handler = Handler(Looper.getMainLooper())
    private var vin: String = ""
    private var isRunning = false

    companion object {
        const val CHANNEL_SERVICE = "carpayin_service"
        const val CHANNEL_EVENTS  = "carpayin_events"
        const val NOTIF_SERVICE   = 1
        const val NOTIF_PARKING   = 2
        const val NOTIF_PAYMENT   = 3

        private const val FEE_POLL_MS   = 60_000L
        private const val MQTT_WATCH_MS = 30_000L

        var onFeeUpdated: ((lotName: String, amount: Int, durationMinutes: Int) -> Unit)? = null
        var onParkingConfirmed: ((lotId: String, sessionId: String) -> Unit)? = null
        var onPaymentComplete: ((txId: String, approvalNo: String, lotId: String, amount: Int) -> Unit)? = null
        var onConnectionChanged: ((connected: Boolean) -> Unit)? = null
        var onLotApproaching: ((lotId: String, lotName: String) -> Unit)? = null

        fun start(context: Context) {
            context.startForegroundService(Intent(context, CarPayInService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CarPayInService::class.java))
        }
    }

    private val feePollRunnable = object : Runnable {
        override fun run() {
            if (ParkingStateManager.isParked(applicationContext)) pollFee()
            handler.postDelayed(this, FEE_POLL_MS)
        }
    }

    private val mqttWatchRunnable = object : Runnable {
        override fun run() {
            if (!MqttManager.isConnected() && vin.isNotEmpty()) {
                Log.d(TAG, "MQTT 재연결 시도...")
                Thread {
                    MqttManager.connect(vin)
                    handler.post { onConnectionChanged?.invoke(MqttManager.isConnected()) }
                }.start()
            }
            handler.postDelayed(this, MQTT_WATCH_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isRunning) return START_STICKY
        isRunning = true

        val notif = buildServiceNotif("CarPayIn 주차 감시 중")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_SERVICE, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIF_SERVICE, notif)
            }
        } catch (e: SecurityException) {
            // 위치 권한 미승인 시 (에뮬레이터 첫 실행 등) → 위치 타입 없이 포그라운드 유지
            Log.w(TAG, "위치 권한 없음 → 기본 포그라운드로 시작: ${e.message}")
            startForeground(NOTIF_SERVICE, notif)
        }
        Log.d(TAG, "서비스 시작")

        VehicleDataManager.init(this)
        vin = VehicleDataManager.readVin(this)

        setupCallbacks()

        Thread {
            runCatching {
                val lots = ApiManager.fetchParkingLots()
                GeofenceManager.updateParkingLots(
                    lots.map { GeofenceManager.ParkingLot(it.id, it.name, it.lat, it.lng) }
                )
                Log.d(TAG, "주차장 목록 갱신 완료: ${lots.size}개")
            }.onFailure {
                Log.w(TAG, "주차장 목록 조회 실패: ${it.message}")
            }
        }.start()

        Thread {
            MqttManager.connect(vin)
            handler.post { onConnectionChanged?.invoke(MqttManager.isConnected()) }
        }.start()

        GeofenceManager.start(this)

        handler.postDelayed(feePollRunnable, FEE_POLL_MS)
        handler.postDelayed(mqttWatchRunnable, MQTT_WATCH_MS)

        if (ParkingStateManager.isParked(this)) {
            val lotId = ParkingStateManager.getLotId(this)
            updateServiceNotif("🅿 주차 중 — $lotId")
            handler.postDelayed({ pollFee() }, 1_000)
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacks(feePollRunnable)
        handler.removeCallbacks(mqttWatchRunnable)
        GeofenceManager.stop()
        MqttManager.disconnect()
        VehicleDataManager.release()
        Log.d(TAG, "서비스 종료")
    }

    private fun setupCallbacks() {
        VehicleDataManager.onIgnitionChanged = { ignitionOn ->
            if (ignitionOn && ParkingStateManager.isParked(this)) {
                Log.d(TAG, "시동 ON + 주차 중 → 요금 자동 조회")
                handler.post { pollFee() }
            }
        }

        MqttManager.onParkingConfirmed = { lotId, sessionId ->
            Log.d(TAG, "입차 확정 수신: $lotId / $sessionId")
            ParkingStateManager.saveParkingState(this, true, lotId, sessionId)
            handler.post {
                onParkingConfirmed?.invoke(lotId, sessionId)
                updateServiceNotif("🅿 주차 중 — $lotId")
            }
            showEventNotif(NOTIF_PARKING, "🅿 입차 확인", "$lotId 에 입차되었습니다")
            handler.postDelayed({ pollFee() }, 1_000)
        }

        MqttManager.onPaymentComplete = { txId, approvalNo, lotId, amount ->
            Log.d(TAG, "결제 완료 수신: $txId / ${"%,d".format(amount)}원")
            TransactionStore.save(this, txId, lotId, amount)
            ParkingStateManager.saveParkingState(this, false)
            handler.post {
                onPaymentComplete?.invoke(txId, approvalNo, lotId, amount)
                updateServiceNotif("CarPayIn 주차 감시 중")
            }
            showEventNotif(
                NOTIF_PAYMENT,
                "✓ 결제 완료 — ${"%,d".format(amount)}원",
                "$lotId | 승인번호: $approvalNo"
            )
        }

        GeofenceManager.onParkingLotApproach = geofence@{ lotId, lotName, triggerType ->
            handler.post { onLotApproaching?.invoke(lotId, lotName) }
            val plate = ParkingStateManager.getPlateNumber(this) ?: return@geofence
            val token = getValidToken() ?: return@geofence
            Thread {
                runCatching {
                    ApiManager.sendPreNotification(vin, plate, lotId, triggerType, token)
                    Log.d(TAG, "사전 알림 전송 완료: $lotId ($triggerType)")
                }.onFailure {
                    Log.e(TAG, "사전 알림 실패: ${it.message}")
                }
            }.start()
        }
    }

    private fun pollFee() {
        val lotId     = ParkingStateManager.getLotId(this)
        val sessionId = ParkingStateManager.getSessionId(this)
        val token     = getValidToken() ?: return

        Thread {
            runCatching {
                val fee = ApiManager.queryFee(lotId, sessionId, token)
                handler.post {
                    onFeeUpdated?.invoke(fee.lotName, fee.amount, fee.durationMinutes)
                    updateServiceNotif("🅿 주차 중 — ${fee.lotName} | ${"%,d".format(fee.amount)}원")
                }
            }.onFailure {
                Log.e(TAG, "요금 polling 실패: ${it.message}")
            }
        }.start()
    }

    private fun getValidToken(): String? {
        val token   = ParkingStateManager.getAccessToken(this) ?: return null
        val expiry  = ParkingStateManager.getTokenExpiry(this)
        val refresh = ParkingStateManager.getRefreshToken(this)

        val needsRefresh = refresh != null &&
            expiry > 0 &&
            System.currentTimeMillis() > expiry - 5 * 60_000L

        if (needsRefresh) {
            return try {
                val result = ApiManager.refreshToken(refresh!!)
                ParkingStateManager.saveTokens(this, result.accessToken, result.refreshToken)
                Log.d(TAG, "액세스 토큰 자동 갱신 완료")
                result.accessToken
            } catch (e: Exception) {
                Log.e(TAG, "토큰 갱신 실패, 기존 토큰 사용: ${e.message}")
                token
            }
        }
        return token
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "CarPayIn 서비스", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "백그라운드 주차 감시 서비스" }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_EVENTS, "주차·결제 알림", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "입차 확정 및 결제 완료 알림" }
        )
    }

    private fun buildServiceNotif(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_SERVICE)
            .setContentTitle("Car PayIn")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateServiceNotif(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_SERVICE, buildServiceNotif(text))
    }

    private fun showEventNotif(id: Int, title: String, text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val pi = PendingIntent.getActivity(
            this, id,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notif = Notification.Builder(this, CHANNEL_EVENTS)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(id, notif)
    }
}
